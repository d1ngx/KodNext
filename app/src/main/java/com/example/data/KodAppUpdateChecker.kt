package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int = 0,
    val downloadUrl: String,
    val changelog: String = "",
    val forceUpdate: Boolean = false,
    val sourceUrl: String = ""
)

/**
 * Checks a build-time fixed update site or JSON manifest URL (`BuildConfig.APP_UPDATE_URL`)
 * for newer KodNext releases.
 *
 * Supports:
 * 1. Custom JSON manifests (`version.json` / `update.json`), e.g.:
 *    `{"versionName":"0.12","versionCode":25,"downloadUrl":"https://.../KodNext-0.12.apk","changelog":"..."}`
 * 2. GitHub Releases API JSON (`https://api.github.com/repos/<owner>/<repo>/releases/latest`)
 * 3. Plain site base URLs (automatically probes `<base>/version.json`, `<base>/update.json`, `<base>`).
 *
 * Google Play Creator Program compliant: downloads are handed off to the system browser /
 * download URL via `Intent.ACTION_VIEW` without requesting `REQUEST_INSTALL_PACKAGES`.
 */
class KodAppUpdateChecker(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        fun fixedUpdateUrl(): String = resolveFixedUpdateUrl(BuildConfig.APP_UPDATE_URL)

        fun hasFixedUpdateUrl(): Boolean = fixedUpdateUrl().isNotBlank()

        internal fun resolveFixedUpdateUrl(buildConfigUrl: String): String =
            normalizeSiteUrl(buildConfigUrl)

        fun normalizeSiteUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        fun buildCandidateManifestUrls(rawInput: String): List<String> {
            val normalized = normalizeSiteUrl(rawInput)
            if (normalized.isBlank()) return emptyList()
            val lower = normalized.lowercase(Locale.ROOT)
            val looksDirectEndpoint = lower.endsWith(".json") ||
                lower.contains("releases/latest") ||
                lower.contains("api.github.com") ||
                lower.contains("?")
            if (looksDirectEndpoint) {
                return listOf(normalized)
            }
            val base = normalized.trimEnd('/')
            return listOf(
                "$base/version.json",
                "$base/update.json",
                normalized
            ).distinct()
        }

        /**
         * Parses either a custom JSON update manifest or a GitHub Releases API payload.
         */
        fun parseUpdateManifest(rawJson: String, manifestUrl: String): AppUpdateInfo? {
            val trimmed = rawJson.trim()
            if (!trimmed.startsWith("{")) return null
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val dataObj = root.optJSONObject("data")?.takeIf {
                it.has("versionName") || it.has("version") || it.has("latestVersion") || it.has("tag_name")
            } ?: root

            val rawVersion = sequenceOf(
                dataObj.optString("versionName"),
                dataObj.optString("version"),
                dataObj.optString("latestVersion"),
                dataObj.optString("tag_name"),
                dataObj.optString("name")
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                .orEmpty()
                .removePrefix("v")
                .removePrefix("V")
                .trim()

            if (rawVersion.isBlank()) return null

            val versionCode = sequenceOf(
                dataObj.optInt("versionCode", 0),
                dataObj.optInt("build", 0),
                dataObj.optInt("code", 0)
            ).firstOrNull { it > 0 } ?: 0

            // Extract APK / download URL (direct fields or GitHub Releases assets[])
            var rawDownloadUrl = sequenceOf(
                dataObj.optString("downloadUrl"),
                dataObj.optString("apkUrl"),
                dataObj.optString("download_url"),
                dataObj.optString("packageUrl"),
                dataObj.optString("url")
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                .orEmpty()

            if (rawDownloadUrl.isBlank()) {
                val assets = dataObj.optJSONArray("assets")
                if (assets != null) {
                    var fallbackAssetUrl = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val assetName = asset.optString("name").lowercase(Locale.ROOT)
                        val assetUrl = asset.optString("browser_download_url").trim()
                        if (assetUrl.isNotBlank()) {
                            if (assetName.endsWith(".apk")) {
                                rawDownloadUrl = assetUrl
                                break
                            }
                            if (fallbackAssetUrl.isBlank()) {
                                fallbackAssetUrl = assetUrl
                            }
                        }
                    }
                    if (rawDownloadUrl.isBlank()) {
                        rawDownloadUrl = fallbackAssetUrl
                    }
                }
            }
            if (rawDownloadUrl.isBlank()) {
                rawDownloadUrl = dataObj.optString("html_url").trim()
            }

            val resolvedDownloadUrl = resolveUrl(rawDownloadUrl, manifestUrl)
            if (resolvedDownloadUrl.isBlank()) return null

            val changelog = sequenceOf(
                dataObj.optString("changelog"),
                dataObj.optString("releaseNotes"),
                dataObj.optString("notes"),
                dataObj.optString("body"),
                dataObj.optString("description")
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                .orEmpty()

            val forceUpdate = dataObj.optBoolean("forceUpdate", false) ||
                dataObj.optBoolean("force", false) ||
                dataObj.optInt("forceUpdate", 0) == 1

            return AppUpdateInfo(
                versionName = rawVersion,
                versionCode = versionCode,
                downloadUrl = resolvedDownloadUrl,
                changelog = changelog,
                forceUpdate = forceUpdate,
                sourceUrl = manifestUrl
            )
        }

        /**
         * Returns true if the remote version is strictly newer than the installed version.
         * Supports both `versionCode` comparison and dotted numeric version comparison (`0.05` -> `0.06`, `0.10` > `0.09`).
         */
        fun isNewerVersion(
            currentVersionName: String,
            currentVersionCode: Int,
            remoteVersionName: String,
            remoteVersionCode: Int
        ): Boolean {
            if (remoteVersionCode > 0 && currentVersionCode > 0 && remoteVersionCode != currentVersionCode) {
                return remoteVersionCode > currentVersionCode
            }
            val currentParts = parseVersionNumbers(currentVersionName)
            val remoteParts = parseVersionNumbers(remoteVersionName)
            val maxLen = maxOf(currentParts.size, remoteParts.size)
            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val r = remoteParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionNumbers(version: String): List<Int> {
            val cleaned = version.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            return cleaned.split('.').map { segment ->
                segment.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
        }

        private fun resolveUrl(rawUrl: String, manifestUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return ""
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            return runCatching {
                URI(manifestUrl).resolve(trimmed).toString()
            }.getOrDefault("")
        }
    }

    /**
     * Checks the configured update endpoint and returns:
     * - `Result.success(AppUpdateInfo)` when a newer version is available
     * - `Result.success(null)` when already up to date
     * - `Result.failure(...)` when the update site is unreachable or returns invalid JSON
     */
    suspend fun checkForUpdate(
        siteOrManifestUrl: String,
        currentVersionName: String,
        currentVersionCode: Int
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        val candidates = buildCandidateManifestUrls(siteOrManifestUrl)
        if (candidates.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请先在设置中填写更新检测站点地址"))
        }

        var lastError: Throwable? = null
        for (candidateUrl in candidates) {
            try {
                val req = Request.Builder()
                    .url(candidateUrl)
                    .header("Accept", "application/json, */*")
                    .header("User-Agent", KodBoxApiClient.buildKodCloudAppUserAgent())
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IllegalStateException("更新站点返回 HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseUpdateManifest(body, candidateUrl)
                    if (parsed == null) {
                        lastError = IllegalStateException("更新清单格式无效，需包含 versionName 与 downloadUrl")
                        return@use
                    }
                    val hasUpdate = isNewerVersion(
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        remoteVersionName = parsed.versionName,
                        remoteVersionCode = parsed.versionCode
                    )
                    return@withContext Result.success(if (hasUpdate) parsed else null)
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IllegalStateException("无法连接更新检测站点"))
    }
}
