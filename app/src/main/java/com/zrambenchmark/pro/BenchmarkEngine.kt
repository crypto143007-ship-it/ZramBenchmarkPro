package com.zrambenchmark.pro

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max

/** Every result is explicitly tagged with its execution domain. No library result is called a kernel-ZRAM result. */
data class AlgorithmInfo(val name: String, val available: Boolean, val domain: String, val detail: String)
data class BenchmarkResult(
    val algorithm: String,
    val domain: String,
    val originalBytes: Long,
    val compressedBytes: Long,
    val ratio: Double,
    val savedBytes: Long,
    val compressionMBps: Double,
    val decompressionMBps: Double,
    val compressionCpuMs: Double,
    val decompressionCpuMs: Double,
    val repetitions: Int,
    val medianCompressionMs: Double,
    val medianDecompressionMs: Double
)
data class SystemSnapshot(
    val device: String, val android: String, val kernel: String, val abi: String,
    val cores: Int, val totalRamKb: Long, val availableRamKb: Long,
    val zramDevice: String, val zramSize: String, val currentAlgorithm: String,
    val algorithms: List<String>, val mmStat: String, val swaps: String,
    val batteryPct: Int?, val batteryTempC: Double?, val batteryCurrentUa: Int?, val batteryVoltageMv: Int?
)

object BenchmarkEngine {
    private val zramRoots = listOf("/sys/block/zram0", "/sys/block/zram1", "/sys/block/zram2")

    fun readText(path: String): String? = try { File(path).readText().trim() } catch (_: Exception) { null }

    fun detectAlgorithms(): List<AlgorithmInfo> {
        val result = mutableListOf<AlgorithmInfo>()
        val devices = zramRoots.filter { File(it).exists() }
        val names = linkedSetOf<String>()
        devices.forEach { root ->
            val raw = readText("$root/comp_algorithm")
            if (raw != null) raw.split(Regex("\\s+")).forEach { token ->
                val clean = token.removePrefix("[").removeSuffix("]")
                if (clean.isNotBlank()) names.add(clean)
            }
        }
        names.forEach { result += AlgorithmInfo(it, true, "Kernel ZRAM", "Advertised by device sysfs; not yet benchmarked") }
        if (names.isEmpty()) result += AlgorithmInfo("Kernel algorithms", false, "Kernel ZRAM", "No readable /sys/block/zram*/comp_algorithm interface")
        result += AlgorithmInfo("deflate (JDK)", true, "Standalone library", "Measured with java.util.zip; not equivalent to kernel ZRAM")
        listOf("lz4", "lz4hc", "lz4kd", "lz4k", "lz4k-v2", "zstd", "lzo", "lzo-rle", "842").forEach { n ->
            if (names.none { it.equals(n, true) }) result += AlgorithmInfo(n, false, "Kernel ZRAM", "Not advertised by readable ZRAM sysfs")
        }
        return result
    }

    fun snapshot(context: Context): SystemSnapshot {
        val mem = readText("/proc/meminfo").orEmpty().lines()
        fun kb(key: String) = mem.firstOrNull { it.startsWith("$key:") }?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLongOrNull() ?: 0L
        val zram = zramRoots.firstOrNull { File(it).exists() }
        val comp = zram?.let { readText("$it/comp_algorithm") }
        val active = comp?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("[") }?.removeSurrounding("[", "]") ?: "Unavailable"
        val algorithms = comp?.split(Regex("\\s+"))?.map { it.removePrefix("[").removeSuffix("]") }?.filter { it.isNotBlank() } ?: emptyList()
        val size = zram?.let { readText("$it/disksize")?.toLongOrNull()?.let { b -> "${"%.1f".format(b / 1073741824.0)} GiB" } } ?: "Unavailable"
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Exception) { Int.MIN_VALUE }
        val stat = zram?.let { readText("$it/mm_stat") } ?: "Unavailable"
        val swaps = readText("/proc/swaps") ?: "Unavailable"
        return SystemSnapshot(
            Build.MODEL, "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            readText("/proc/version")?.take(110) ?: "Unavailable", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
            Runtime.getRuntime().availableProcessors(), kb("MemTotal"), kb("MemAvailable"), zram ?: "Not detected", size,
            active, algorithms, stat, swaps,
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            if (temp >= 0) temp / 10.0 else null,
            if (current != Int.MIN_VALUE) current else null, if (voltage >= 0) voltage else null
        )
    }

    /** Observes the already-active system ZRAM using ordinary app permissions. Never writes to a ZRAM block device. */
    suspend fun runLiveActive(repetitions: Int = 5, dataSizeMiB: Int = 8): BenchmarkResult = withContext(Dispatchers.IO) {
        fun readCounters(path: String): Map<String, Long> = readText(path).orEmpty().lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 2) p[0] to (p[1].toLongOrNull() ?: return@mapNotNull null) else null
        }.toMap()
        fun vmSwap(): Pair<Long, Long> {
            val m = readCounters("/proc/vmstat")
            return (m["pswpout"] ?: 0L) to (m["pswpin"] ?: 0L)
        }
        // Linux block-stat sectors are 512 bytes: field 3 is sectors read, field 7 sectors written.
        fun blockIo(root: String): Pair<Long, Long>? {
            val v = readText("$root/stat")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: return null
            if (v.size < 7) return null
            return v[6] to v[2] // bytes-out sectors, bytes-in sectors
        }
        fun mm(root: String): Pair<Long, Long>? {
            val values = readText("$root/mm_stat")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: return null
            if (values.size < 2) return null
            return values[0] to values[1]
        }
        val swapText = readText("/proc/swaps")
        val swapLine = swapText?.lines()?.drop(1)?.firstOrNull { it.contains("zram") }
        val root: String
        val detection: String
        if (swapLine != null) {
            val index = Regex("zram(\\d+)").find(swapLine)?.groupValues?.get(1)
                ?: error("Cannot identify the active ZRAM device in /proc/swaps")
            root = "/sys/block/zram$index"
            detection = "active swap confirmed"
        } else {
            val swapTotal = readText("/proc/meminfo")?.lineSequence()
                ?.firstOrNull { it.startsWith("SwapTotal:") }?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLongOrNull() ?: 0L
            val candidates = zramRoots.filter { File(it).exists() && readText("$it/comp_algorithm")?.contains("[") == true }
            if (swapText == null && swapTotal > 0 && candidates.size == 1) {
                root = candidates.single()
                detection = "active ZRAM inferred from SwapTotal and the only selected ZRAM device"
            } else {
                error("Android does not expose /proc/swaps to this app, so the active ZRAM device cannot be confirmed without root. No ZRAM device was modified.")
            }
        }
        if (!File(root).exists()) error("The active ZRAM sysfs device is not readable by this app")
        val codec = readText("$root/comp_algorithm")?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("[") }?.removeSurrounding("[", "]")
            ?: "Selected codec unavailable (sysfs permission restricted)"
        val initialMm = mm(root)
        val initialSwap = vmSwap()\n        val initialBlock = blockIo(root)
        val rounds = repetitions.coerceIn(1, 10)
        val totalBytes = dataSizeMiB.coerceIn(1, 32) * 1024 * 1024
        val retained = ArrayList<ByteArray>()
        try {
            // One small warm-up allocation is deliberately excluded from the observed interval.
            val warmup = ByteArray(256 * 1024) { (it * 31).toByte() }
            warmup[0] = 1
            val warmStart = vmSwap()
            val baselineMm = mm(root) ?: initialMm
            val baselineSwap = vmSwap()\n            val baselineBlock = blockIo(root)
            val chunkSize = max(4096, totalBytes / rounds)
            val startNs = System.nanoTime()
            repeat(rounds) {
                val chunk = ByteArray(chunkSize.coerceAtMost(totalBytes - retained.sumOf { it.size })) 
                var x = 0x13579B
                var offset = 0
                while (offset < chunk.size) {
                    x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                    chunk[offset] = (x and 0xff).toByte()
                    offset += 4096
                }
                retained.add(chunk)
                // Keep the touched anonymous pages resident in the test process while Android handles reclaim.
                Thread.sleep(700)
            }
            val elapsedSeconds = ((System.nanoTime() - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
            val finalSwap = vmSwap()\n            val finalBlock = blockIo(root)
            val finalMm = mm(root) ?: baselineMm
            val outPages = (finalSwap.first - baselineSwap.first).coerceAtLeast(0L)
            val inPages = (finalSwap.second - baselineSwap.second).coerceAtLeast(0L)
            val pageBytes = 4096L
            val swappedOut = if (baselineBlock != null && finalBlock != null)
                (finalBlock.first - baselineBlock.first).coerceAtLeast(0L) * 512L else outPages * pageBytes
            val swappedIn = if (baselineBlock != null && finalBlock != null)
                (finalBlock.second - baselineBlock.second).coerceAtLeast(0L) * 512L else inPages * pageBytes
            val original = finalMm?.first ?: initialMm?.first ?: 0L
            val compressed = finalMm?.second ?: initialMm?.second ?: 0L
            val ratio = if (compressed > 0L) original.toDouble() / compressed.toDouble() else 0.0
            val saved = (original - compressed).coerceAtLeast(0L)
            val swapObserved = swappedOut > 0L || swappedIn > 0L
            val domain = if (swapObserved) "Live active ZRAM · device I/O counters · $detection"
                else "Live active ZRAM · no swap traffic observed · $detection"
            BenchmarkResult(
                codec, domain, original, compressed, ratio, saved,
                swappedOut / 1048576.0 / elapsedSeconds,
                swappedIn / 1048576.0 / elapsedSeconds,
                0.0, 0.0, rounds, 0.0, 0.0
            )
        } finally {
            retained.clear()
            System.gc()
        }
    }

    private fun makeDataset(size: Int): ByteArray {
        val data = ByteArray(size)
        var x = 0x12345678
        for (i in data.indices) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            data[i] = when (i % 4) { 0 -> (x and 0xff).toByte(); 1 -> (i % 251).toByte(); 2 -> 0; else -> (x ushr 8).toByte() }
        }
        return data
    }
    private fun median(values: List<Double>): Double { val s = values.sorted(); return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
}
