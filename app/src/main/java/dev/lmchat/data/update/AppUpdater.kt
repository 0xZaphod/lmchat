package dev.lmchat.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class AppRelease(val version: String, val notes: String, val url: String, val size: Long)

/** Release names follow the public numeric versionName; APK versionCode is checked separately. */
internal fun newerVersion(candidate: String, installed: String): Boolean {
    fun parts(value: String): List<Long>? = value.removePrefix("v").split('.').map {
        if (it.isEmpty() || it.any { character -> character !in '0'..'9' }) return null
        it.toLongOrNull() ?: return null
    }
    val next = parts(candidate) ?: return false
    val current = parts(installed) ?: return false
    for (i in 0 until maxOf(next.size, current.size)) {
        val comparison = (next.getOrElse(i) { 0 }).compareTo(current.getOrElse(i) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal fun parseRelease(body: String, installed: String): AppRelease? {
    val release = Json.parseToJsonElement(body).jsonObject
    if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
        release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
    val version = release.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
    if (!newerVersion(version, installed)) return null
    val asset = release.getValue("assets").jsonArray.map { it.jsonObject }.singleOrNull {
        it["name"]?.jsonPrimitive?.content == "LMChat-$version.apk"
    } ?: return null
    val url = asset.getValue("browser_download_url").jsonPrimitive.content
    require(url.startsWith("https://github.com/0xZaphod/lmchat/releases/download/")) { "Unexpected update source" }
    val size = asset.getValue("size").jsonPrimitive.long
    require(size in 1..MAX_APK_SIZE) { "Invalid update size" }
    return AppRelease(version, release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), url, size)
}

private const val MAX_APK_SIZE = 250L * 1024 * 1024

class AppUpdater(private val context: Context) {
    // Independent from server clients: never sends chat credentials and never permits HTTPS downgrade.
    private val client = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    @Suppress("DEPRECATION")
    val installedVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("https://api.github.com/repos/0xZaphod/lmchat/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "LMChat/$installedVersion").build())
            .execute().use { response ->
                if (response.code == 404) return@withContext null
                check(response.isSuccessful) { "Update check failed (HTTP ${response.code})" }
                parseRelease(response.body?.string() ?: error("Empty release response"), installedVersion)
            }
    }

    suspend fun download(release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "update.apk")
        try {
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                val body = response.body ?: error("Empty download")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            check(total <= release.size && total <= MAX_APK_SIZE) { "Unexpected APK size" }
                            output.write(buffer, 0, count)
                            val percent = (total * 100 / release.size).toInt()
                            if (percent != lastPercent) {
                                progress(total.toFloat() / release.size)
                                lastPercent = percent
                            }
                        }
                        check(total == release.size) { "Incomplete download. Please retry." }
                    }
                }
            }
            verify(file)
            file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    internal fun verify(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val update = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        check(update.packageName == context.packageName) { "APK belongs to a different app" }
        val newer = if (Build.VERSION.SDK_INT >= 28) update.longVersionCode > installed.longVersionCode
            else update.versionCode > installed.versionCode
        check(newer) { "APK is not a newer build" }
        val currentSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val updateSigners = if (Build.VERSION.SDK_INT >= 28) update.signingInfo?.apkContentsSigners else update.signatures
        check(!currentSigners.isNullOrEmpty() && !updateSigners.isNullOrEmpty() &&
            currentSigners.toSet() == updateSigners.toSet()) {
            "Update signing key does not match this installation. Install the official release to use its updates."
        }
        // Android's installer performs the authoritative cryptographic APK signature verification.
    }
}
