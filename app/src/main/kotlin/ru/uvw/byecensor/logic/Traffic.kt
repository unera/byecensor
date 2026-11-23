package ru.uvw.byecensor.logic

import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Traffic the kernel counted, read out of it and folded into the app's own sums.
 *
 * The kernel keeps its counters in memory: every restart — a network change, a failed heartbeat,
 * a settings change — starts them at zero. So the app reads them with `-reset`, which empties
 * the kernel's counters in the same breath, and adds what it read to the two numbers it keeps on
 * disk. Nothing counted twice, nothing lost, and the kernel never holds a value the app has not
 * already taken.
 *
 * The reading goes through the kernel's own command line (`libxray.so api statsquery`) instead of
 * a gRPC client: the API is a protobuf service, and a protobuf schema plus a library is a lot of
 * APK for two integers. The binary is already there, and is already started from the same place.
 */
object Traffic {
    /** Loopback only: whatever can reach this port can read and reset the counters. */
    const val API_PORT = 12099

    /** What one reading says: everything counted since the previous one. */
    data class Read(val up: Long, val down: Long)

    /**
     * Ask the kernel for the counters and reset them, in one call.
     *
     * Every inbound is summed — the SOCKS door, the HTTP door, both WhatsApp doors — because the
     * question on the screen is "how much went through this phone", not "how much went through
     * one door". The API door itself is skipped: it counts the questions, not the traffic.
     *
     * @returns null when the kernel did not answer (not started yet, or the port is taken).
     */
    fun query(binary: File, assets: File, apiPort: Int = API_PORT): Read? {
        if (!binary.exists()) return null
        val builder = ProcessBuilder(
            binary.absolutePath, "api", "statsquery",
            "--server=127.0.0.1:$apiPort",
            "-pattern", "inbound>>>",
            "-reset",
        )
        builder.environment()["XRAY_LOCATION_ASSET"] = assets.absolutePath
        builder.redirectErrorStream(true)
        val process = runCatching { builder.start() }.getOrNull() ?: return null
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        runCatching { process.destroy() }

        // the CLI prints its banner around the JSON; the answer is the outermost braces
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull() ?: return null
        val stat = json.optJSONArray("stat") ?: return Read(0, 0)

        var up = 0L
        var down = 0L
        for (index in 0 until stat.length()) {
            val item = stat.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (!name.startsWith("inbound>>>")) continue
            if (name.startsWith("inbound>>>api>>>")) continue
            val value = item.optLong("value", 0)
            when {
                name.endsWith(">>>uplink") -> up += value
                name.endsWith(">>>downlink") -> down += value
            }
        }
        return Read(up, down)
    }

    /** Bytes as a person reads them: a number a screen has room for and a unit that says the scale. */
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit += 1
        }
        val text = when {
            value < 10 -> String.format(Locale.ROOT, "%.2f", value)
            value < 100 -> String.format(Locale.ROOT, "%.1f", value)
            else -> String.format(Locale.ROOT, "%.0f", value)
        }
        return "$text ${units[unit]}"
    }
}
