package com.iris.localai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.iris.localai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * ETC fork (OS-ETC-IRIDIS) v2.3.1: check for updates against the project's GitHub Releases.
 *
 * Queries ETQWFD/OS-ETC-IRIDIS /releases/latest, compares the tag with the installed
 * version (BuildConfig.VERSION_NAME) and hands the APK's download URL to the browser.
 * Pure HttpURLConnection + kotlinx.serialization, no new dependencies.
 */
object Updater {
    const val REPO = "ETQWFD/OS-ETC-IRIDIS"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    /** ETC fork v2.3.2: GitHub anonymous API is rate-limited (60/h/IP) and slow in China —
     * try the API first, then ghproxy mirrors of it. */
    private val CHANNELS = listOf(
        API,
        "https://ghproxy.net/$API",
        "https://gh.api.99988866.xyz/$API",
        "https://mirror.ghproxy.com/$API",
    )
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
    )

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String = "",
        val assets: List<Asset> = emptyList(),
    )

    data class Result(val tag: String? = null, val apkUrl: String? = null, val error: String? = null)

    /** One round-trip to GitHub Releases/latest over the mirror chain. Never throws. */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        var lastErr: String? = null
        for (url in CHANNELS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "OS-ETC-IRIDIS/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        lastErr = "HTTP $code"
                        continue
                    }
                    val release = json.decodeFromString<Release>(
                        conn.inputStream.bufferedReader().use { it.readText() },
                    )
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.url
                    return@withContext Result(tag = release.tagName.ifBlank { null }, apkUrl = apk)
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                lastErr = t.message ?: "网络错误"
            }
        }
        Result(error = lastErr ?: "无法连接 GitHub")
    }

    /** Open the APK direct link in the browser; falls back to the Release page. */
    fun openDownload(ctx: Context, apkUrl: String?, tag: String) {
        val url = apkUrl ?: "https://github.com/$REPO/releases/tag/$tag"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "2.3.1-etc" vs tag "v2.4.0-etc" → numeric compare. true = the tag is newer. */
    fun hasNewer(currentVersionName: String, latestTag: String?): Boolean {
        val latest = latestTag?.trim()?.removePrefix("v")?.removeSuffix("-etc") ?: return false
        val cur = currentVersionName.trim().removePrefix("v").removeSuffix("-etc")
        val a = cur.split('.').mapNotNull { it.toIntOrNull() }
        val b = latest.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }
}
