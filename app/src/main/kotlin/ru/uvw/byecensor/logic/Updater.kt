package ru.uvw.byecensor.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer release, looked up on GitHub and put on the phone.
 *
 * The release list is the only place that knows about a version this app has not seen, and the
 * APK in it is the same file a person would download by hand. Nothing is installed from here:
 * the system installer is opened with the downloaded file, and the person says yes in its own
 * dialog — that dialog is the permission, not this code.
 */
object Updater {
    /** Releases of the very app this is. */
    const val REPO = "unera/byecensor"

    /** Where the APK of the newest release lives. */
    const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** How often to look, in hours. Zero means never. */
    enum class Interval(val key: String, val label: String, val hours: Long) {
        NEVER("never", "никогда", 0),
        HOURLY("hourly", "ежечасно", 1),
        DAILY("daily", "ежедневно", 24),
        WEEKLY("weekly", "еженедельно", 24 * 7),
        ;

        companion object {
            val DEFAULT = DAILY
            fun of(key: String?): Interval = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    data class Release(val version: String, val url: String, val notes: String)

    /** The version of the running app, as the person sees it in «О приложении». */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("0")

    /**
     * The newest release, if GitHub answers with one.
     *
     * A release without an `.apk` asset is not an update this app can install — draft and source
     * -only tags happen — so it is skipped rather than reported and then failing on download.
     */
    fun latest(timeoutMs: Int = 12_000): Release {
        val body = getText(API, timeoutMs)
        val json = JSONObject(body)
        val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        require(version.isNotBlank()) { "в ответе GitHub нет номера версии" }
        val assets = json.optJSONArray("assets") ?: throw IOException("у релиза нет файлов")
        var url = ""
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            url = asset.optString("browser_download_url")
            if (url.isNotBlank()) break
        }
        require(url.isNotBlank()) { "в релизе $version нет APK" }
        return Release(version, url, json.optString("name"))
    }

    /**
     * Is `candidate` newer than `current`?
     *
     * Compared number by number, not as strings: "0.10.0" is newer than "0.9.28", and a plain
     * string comparison says the opposite. A missing part counts as zero, so 0.9 and 0.9.0 agree.
     */
    fun newer(candidate: String, current: String): Boolean {
        val left = numbers(candidate)
        val right = numbers(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /**
     * Download the APK of `release` into the app's own storage.
     *
     * The file is written next to its final name and renamed only when complete: a download cut
     * short by a dying network would otherwise leave an APK that looks ready and fails in the
     * installer, which is the worst moment to learn about it.
     */
    fun download(context: Context, release: Release): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "byecensor-${release.version}.apk")
        if (target.length() > 15_000_000) return target
        val part = File(dir, "${target.name}.part")
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // GitHub API wants a user agent, and the CDN redirect does not mind one
            setRequestProperty("User-Agent", "ByeCensor/${currentVersion(context)}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub ответил кодом $code")
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            // An APK without the Xray kernel weighs about 6 MB instead of 22: a truncated but
            // successful-looking answer is worse than no update at all.
            if (part.length() < 15_000_000) {
                throw IOException("скачалось ${part.length()} байт — это не целый APK")
            }
            if (expected > 0 && part.length() != expected) {
                throw IOException("скачалось ${part.length()} из $expected байт")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("не удалось переименовать ${part.name}")
            return target
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    /** What one complete check ended with: a sentence for the screen and, if any, the file. */
    data class Outcome(val text: String, val version: String? = null, val file: File? = null)

    /**
     * Ask GitHub, compare, download when newer.
     *
     * Both the launch check and the «проверить сейчас» row go through here, so the two cannot
     * disagree about which version counts as new.
     */
    fun checkAndDownload(context: Context): Outcome {
        val current = currentVersion(context)
        val release = try {
            latest()
        } catch (error: Throwable) {
            return Outcome("проверка не удалась: " + (error.message ?: "GitHub не ответил"))
        }
        if (!newer(release.version, current)) return Outcome("обновлений нет: $current — последняя")
        val file = try {
            download(context, release)
        } catch (error: Throwable) {
            return Outcome(
                "есть ${release.version}, но скачать не вышло: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
        return Outcome("скачано ${release.version}", release.version, file)
    }

    /** May this app hand an APK to the installer at all? */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The system screen where the person allows this app to install packages. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Hand the downloaded file to the system installer.
     *
     * A `file://` URI is refused by the installer since Android 7, so the file goes through the
     * provider declared in the manifest — and the read grant has to be explicit, because the
     * installer runs as another process.
     */
    fun install(context: Context, file: File) {
        require(file.exists()) { "файла обновления больше нет: ${file.name}" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ByeCensor")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub ответил кодом $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }
}
