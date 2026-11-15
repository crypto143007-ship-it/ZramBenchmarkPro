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

    fun readText(path: String): String? = try { File(path).takeIf { it.canRead() }?.readText()?.trim() } catch (_: Exception) { null }

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

    suspend fun runStandalone(repetitions: Int = 5, dataSizeMiB: Int = 8, cancelled: () -> Boolean = { false }): BenchmarkResult = withContext(Dispatchers.Default) {
        val source = makeDataset(dataSizeMiB.coerceIn(1, 32) * 1024 * 1024)
        val compressTimes = mutableListOf<Double>()
        val decompressTimes = mutableListOf<Double>()
        var compressed = ByteArray(0)
        var compCpu = 0L
        var decompCpu = 0L
        repeat(repetitions.coerceIn(1, 20) + 1) { index ->
            if (cancelled()) throw InterruptedException("Benchmark cancelled")
            val cpu0 = Debug.threadCpuTimeNanos()
            val t0 = System.nanoTime()
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, false), 64 * 1024).use { it.write(source) }
            val ct = (System.nanoTime() - t0) / 1e6
            val cpu1 = Debug.threadCpuTimeNanos()
            compressed = out.toByteArray()
            val t1 = System.nanoTime()
            val decoded = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            val dt = (System.nanoTime() - t1) / 1e6
            val cpu2 = Debug.threadCpuTimeNanos()
            if (!decoded.contentEquals(source)) throw IllegalStateException("Round-trip verification failed")
            if (index > 0) { compressTimes += ct; decompressTimes += dt }
            compCpu += max(0L, cpu1 - cpu0); decompCpu += max(0L, cpu2 - cpu1)
        }
        val cMed = median(compressTimes); val dMed = median(decompressTimes)
        val avgC = compressTimes.average(); val avgD = decompressTimes.average()
        BenchmarkResult("deflate (JDK)", "Standalone library", source.size.toLong(), compressed.size.toLong(), source.size.toDouble() / max(1, compressed.size), source.size.toLong() - compressed.size,
            source.size / 1048576.0 / (avgC / 1000.0), source.size / 1048576.0 / (avgD / 1000.0), compCpu / 1e6 / repetitions, decompCpu / 1e6 / repetitions,
            repetitions, cMed, dMed)
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
