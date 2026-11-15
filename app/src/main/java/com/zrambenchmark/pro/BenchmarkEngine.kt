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

    /** Uses a disposable hot-added ZRAM device configured with the codec selected on active swap. Requires root. */
    suspend fun runKernel(repetitions: Int = 5, dataSizeMiB: Int = 8): BenchmarkResult = withContext(Dispatchers.IO) {
        val swapLine = readText("/proc/swaps").orEmpty().lines().drop(1).firstOrNull { it.contains("zram") }
            ?: error("No active ZRAM swap device")
        val index = Regex("zram(\\d+)").find(swapLine)?.groupValues?.get(1) ?: error("Cannot identify active ZRAM device")
        val activeRoot = "/sys/block/zram" + index
        val codec = readText("$activeRoot/comp_algorithm")?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("[") }?.removeSurrounding("[", "]")
            ?: error("Cannot read the codec selected on active ZRAM")
        val bytes = dataSizeMiB.coerceIn(1, 32) * 1024 * 1024
        val rounds = repetitions.coerceIn(1, 20)
        val blocks = bytes / 4096
        val input = File.createTempFile("zbp-kernel-", ".bin")
        val output = File(input.parentFile, input.name + ".out")
        try {
            input.writeBytes(makeDataset(bytes))
            val inPath = input.absolutePath.replace("'", "'\\''")
            val outPath = output.absolutePath.replace("'", "'\\''")
            val d = '$'
            val script = """
                set -eu
                ADD=/sys/class/zram-control/hot_add
                REMOVE=/sys/class/zram-control/hot_remove
                [ -w "${d}ADD" ] || { echo 'ERROR=Kernel lacks writable hot_add'; exit 21; }
                [ -w "${d}REMOVE" ] || { echo 'ERROR=Kernel lacks writable hot_remove; safe cleanup unavailable'; exit 22; }
                BEFORE=" "
                for p in /sys/block/zram*; do [ -e "${d}p" ] && BEFORE="${d}BEFORE ${d}{p##*/} "; done
                echo 1 > "${d}ADD"
                ROOT=""
                for p in /sys/block/zram*; do
                  [ -e "${d}p" ] || continue
                  NAME=${d}{p##*/}
                  case "${d}BEFORE" in *" ${d}NAME "*) ;; *) ROOT="${d}p"; break;; esac
                done
                [ -n "${d}ROOT" ] || { echo 'ERROR=hot_add did not create a new ZRAM device'; exit 23; }
                IDX=${d}{ROOT##*zram}
                DEV=/dev/block/zram${d}IDX
                [ -e "${d}DEV" ] || DEV=/dev/zram${d}IDX
                cleanup() { echo "${d}IDX" > "${d}REMOVE" 2>/dev/null || true; }
                trap cleanup EXIT HUP INT TERM
                [ -w "${d}ROOT/comp_algorithm" ] || { echo 'ERROR=Scratch codec control unavailable'; exit 24; }
                echo '${codec}' > "${d}ROOT/comp_algorithm"
                echo ${bytes} > "${d}ROOT/disksize"
                [ -b "${d}DEV" ] || { echo 'ERROR=Scratch block device missing'; exit 25; }
                IN='${inPath}'; OUT='${outPath}'; : > "${d}OUT"
                WRITES=''; READS=''; i=0
                while [ "${d}i" -lt ${rounds} ]; do
                  T0=${d}(awk '{printf "%.0f", ${d}1*1000}' /proc/uptime)
                  dd if="${d}IN" of="${d}DEV" bs=4096 conv=fsync 2>/dev/null
                  T1=${d}(awk '{printf "%.0f", ${d}1*1000}' /proc/uptime)
                  dd if="${d}DEV" of="${d}OUT" bs=4096 count=$blocks 2>/dev/null
                  T2=${d}(awk '{printf "%.0f", ${d}1*1000}' /proc/uptime)
                  cmp -s "${d}IN" "${d}OUT" || { echo 'ERROR=Read-back verification failed'; exit 26; }
                  W=${d}((T1-T0)); R=${d}((T2-T1))
                  WRITES="${d}WRITES ${d}W"; READS="${d}READS ${d}R"; i=${d}((i+1))
                done
                set -- ${d}(cat "${d}ROOT/mm_stat")
                echo CODEC='${codec}'; echo DEVICE=zram${d}IDX
                echo ORIGINAL_BYTES=${d}1; echo COMPRESSED_BYTES=${d}2
                echo WRITE_MS=${d}WRITES; echo READ_MS=${d}READS; echo VERIFIED=true
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            val response = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                val reason = Regex("ERROR=([^\\r\\n]+)").find(response)?.groupValues?.get(1)
                    ?: response.takeLast(400).ifBlank { "Root command failed (exit $exit)" }
                error(reason)
            }
            fun field(key: String) = Regex("(?m)^" + key + "=(.*)$").find(response)?.groupValues?.get(1)?.trim()
                ?: error("Missing " + key + " in kernel response")
            val original = field("ORIGINAL_BYTES").toLong()
            val compressed = field("COMPRESSED_BYTES").toLong()
            val writes = field("WRITE_MS").split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toDouble() }.filter { it > 0 }
            val reads = field("READ_MS").split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toDouble() }.filter { it > 0 }
            if (field("VERIFIED") != "true" || original <= 0 || compressed <= 0 || writes.isEmpty() || reads.isEmpty()) error("Incomplete kernel benchmark statistics")
            val writeMedian = median(writes); val readMedian = median(reads)
            val device = field("DEVICE")
            BenchmarkResult("$codec ($device)", "Native kernel ZRAM · temporary device", original, compressed,
                original.toDouble() / compressed, original - compressed,
                original / 1048576.0 / (writeMedian / 1000.0), original / 1048576.0 / (readMedian / 1000.0),
                0.0, 0.0, writes.size, writeMedian, readMedian)
        } finally { input.delete(); output.delete() }
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
