package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

sealed interface KodQrPayload {
    data class TokenLogin(
        val serverUrl: String,
        val accessToken: String
    ) : KodQrPayload

    data class CredentialsLogin(
        val serverUrl: String,
        val username: String,
        val password: String
    ) : KodQrPayload

    /**
     * A server URL containing dynamic QR login / callback parameters (e.g. `?user/index/qrcode&...`,
     * `?user/view/call&...`, `?s=...`, short links, or signed parameters) that must be exchanged with the server.
     */
    data class AuthUrl(
        val serverUrl: String,
        val fullUrl: String
    ) : KodQrPayload

    data class ServerOnly(
        val serverUrl: String
    ) : KodQrPayload

    data class Unsupported(
        val raw: String,
        val reason: String
    ) : KodQrPayload
}

/**
 * Parses QR codes scanned from the web Account Center (top-right avatar QR) or server login pages.
 * Supports:
 * 1. Authenticated URLs with query/fragment/path tokens (`accessToken`, `access_token`, `loginToken`, `token`, `sid`, etc.)
 * 2. Wrapper URLs carrying an embedded server URL parameter (`?url=https://...&token=...`)
 * 3. Dynamic server QR callback URLs (`?user/index/qrcode&...`, `?user/view/call&...`, short paths, Base64 params, etc.)
 * 4. Structured JSON (flat or nested under `data`/`info`/`result`/`payload`) or Base64-encoded JSON/URL payloads
 * 5. Custom scheme URIs (`kodbox://...`, `kodcloud://...`, `kod://...`, `nc://login/user:...&password:...&server:...`)
 * 6. Plain server URLs (`https://cloud.example.com` or `cloud.example.com`)
 */
object KodQrLoginParser {

    private val urlKeys = listOf(
        "url", "server", "serverurl", "host", "serverhost", "site", "siteurl",
        "apiurl", "appurl", "domain", "baseurl", "kodhost", "kodurl", "link",
        "href", "address", "addr", "api", "origin"
    )

    private val primaryTokenKeys = listOf(
        "accesstoken", "logintoken", "usertoken", "kodtoken", "authtoken",
        "apptoken", "sessiontoken", "token", "kodsessionid", "sessionid",
        "sid", "ticket", "utoken", "secret"
    )

    private val shortTokenKeys = listOf("tk", "at", "lt", "t", "k", "a", "auth")

    private val encodedContainerKeys = listOf(
        "data", "info", "payload", "param", "params", "content", "state", "d", "p", "q"
    )

    private val trivialFragments = setOf(
        "", "/", "explorer", "/explorer", "desktop", "/desktop",
        "files", "/files", "setting", "/setting", "setting/user", "/setting/user"
    )

    fun parse(rawInput: String): KodQrPayload = parseInternal(rawInput, depth = 0)

    /**
     * Produces a safe, non-secret diagnostic hint describing the QR payload structure
     * (host + route/keys only, never printing token values).
     */
    fun describeSafely(rawInput: String): String {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return "空内容"
        if (raw.startsWith("{") && raw.endsWith("}")) {
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            val keys = obj?.keys()?.asSequence()?.take(5)?.joinToString(",")
            return if (!keys.isNullOrBlank()) "JSON($keys)" else "JSON"
        }
        val parsed = raw.toHttpUrlOrNull()
        if (parsed != null) {
            val pathPart = parsed.encodedPath.takeIf { it != "/" }.orEmpty()
            val queryKeys = parsed.encodedQuery.orEmpty()
                .split("&")
                .map { it.substringBefore("=").take(24) }
                .filter { it.isNotBlank() }
                .take(4)
                .joinToString("&")
            val fragHint = parsed.encodedFragment?.takeIf { it.isNotBlank() }?.let { "#${it.substringBefore("=").take(18)}" }.orEmpty()
            return buildString {
                append(parsed.host)
                if (pathPart.isNotBlank()) append(pathPart.take(28))
                if (queryKeys.isNotBlank()) append("?$queryKeys")
                if (fragHint.isNotBlank()) append(fragHint)
            }
        }
        return raw.substringBefore("?").take(32)
    }

    private fun parseInternal(rawInput: String, depth: Int): KodQrPayload {
        val raw = rawInput.trim()
        if (raw.isEmpty()) {
            return KodQrPayload.Unsupported(raw, "二维码内容为空")
        }

        // 1. JSON payload (flat or nested)
        if (raw.startsWith("{") && raw.endsWith("}")) {
            parseJsonPayload(raw, depth)?.let { return it }
        }

        // 2. Nextcloud-style nc://login/user:xxx&password:yyy&server:https://...
        if (raw.startsWith("nc://login/", ignoreCase = true)) {
            parseNcLoginUri(raw)?.let { return it }
        }

        // 3. Custom kodbox://, kodcloud://, kod://, kodnext:// scheme
        if (raw.startsWith("kodbox://", ignoreCase = true) ||
            raw.startsWith("kodcloud://", ignoreCase = true) ||
            raw.startsWith("kod://", ignoreCase = true) ||
            raw.startsWith("kodnext://", ignoreCase = true)
        ) {
            parseCustomScheme(raw, depth)?.let { return it }
        }

        // 4. Standard HTTP/HTTPS URL
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return parseHttpUrlPayload(raw, depth)
        }

        // 5. Base64-encoded JSON or URL payload
        if (depth < 2 && !raw.contains(" ") && raw.length >= 16) {
            tryDecodeBase64Payload(raw)?.let { decoded ->
                val nested = parseInternal(decoded, depth = depth + 1)
                if (nested !is KodQrPayload.Unsupported) return nested
            }
        }

        // 6. Bare hostname / IP:port (e.g., demo.box.kodcloud.com)
        val normalizedBare = KodFormatters.normalizeServerUrl(raw).getOrNull()
        if (normalizedBare != null && looksLikeHost(raw)) {
            return KodQrPayload.ServerOnly(normalizedBare)
        }

        return KodQrPayload.Unsupported(
            raw = raw,
            reason = "未识别为有效的网盘登录二维码，请扫描网页端「账号中心」右上角二维码或服务器地址二维码"
        )
    }

    private fun parseJsonPayload(raw: String, depth: Int): KodQrPayload? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val flat = linkedMapOf<String, String>()
        flattenJsonStrings(root, flat, maxDepth = 3)
        if (flat.isEmpty()) return null

        val urlCandidate = urlKeys.firstNotNullOfOrNull { key -> flat[key]?.takeIf { it.isNotBlank() } }
            ?: flat.values.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null

        // Check if urlCandidate itself already embeds a token or auth route
        if (depth < 2 && (urlCandidate.contains("?") || urlCandidate.contains("#"))) {
            val nestedFromUrl = parseHttpUrlPayload(urlCandidate, depth + 1)
            if (nestedFromUrl is KodQrPayload.TokenLogin || nestedFromUrl is KodQrPayload.CredentialsLogin) {
                return nestedFromUrl
            }
        }

        val baseServer = extractBaseServerUrl(urlCandidate) ?: return null

        val token = extractTokenFromNormalizedMap(flat)
        if (!token.isNullOrBlank()) {
            return KodQrPayload.TokenLogin(baseServer, token)
        }

        val username = listOf("username", "name", "user", "account", "loginname")
            .firstNotNullOfOrNull { flat[it]?.takeIf { v -> v.isNotBlank() } }
        val password = listOf("password", "pwd", "pass", "passwd")
            .firstNotNullOfOrNull { flat[it]?.takeIf { v -> v.isNotBlank() } }
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            return KodQrPayload.CredentialsLogin(baseServer, username, password)
        }

        // Check if urlCandidate is a full auth callback URL (e.g. {"url": "https://host/?user/index/qrcode&..."})
        if (depth < 2 && (urlCandidate.startsWith("http://", true) || urlCandidate.startsWith("https://", true))) {
            val nestedFromUrl = parseHttpUrlPayload(urlCandidate, depth + 1)
            if (nestedFromUrl is KodQrPayload.AuthUrl) {
                return nestedFromUrl
            }
        }

        // Check if any Base64 container inside JSON holds the real token/URL
        if (depth < 2) {
            for (k in encodedContainerKeys) {
                val encodedVal = flat[k] ?: continue
                val decoded = tryDecodeBase64Payload(encodedVal) ?: continue
                val nested = parseInternal(decoded, depth + 1)
                if (nested is KodQrPayload.TokenLogin || nested is KodQrPayload.CredentialsLogin || nested is KodQrPayload.AuthUrl) {
                    return nested
                }
            }
        }

        return KodQrPayload.ServerOnly(baseServer)
    }

    private fun flattenJsonStrings(obj: JSONObject, out: MutableMap<String, String>, maxDepth: Int) {
        if (maxDepth < 0) return
        val keys = obj.keys()
        val nestedObjects = mutableListOf<JSONObject>()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val normKey = normalizeKey(rawKey)
            val value = obj.opt(rawKey)
            when (value) {
                is JSONObject -> nestedObjects.add(value)
                is String -> {
                    val clean = value.trim()
                    if (clean.isNotEmpty() && !clean.equals("null", ignoreCase = true)) {
                        out.putIfAbsent(normKey, clean)
                    }
                }
                is Number, is Boolean -> {
                    out.putIfAbsent(normKey, value.toString())
                }
            }
        }
        nestedObjects.forEach { child -> flattenJsonStrings(child, out, maxDepth - 1) }
    }

    private fun parseNcLoginUri(raw: String): KodQrPayload? {
        val body = raw.substringAfter("nc://login/", "")
        if (body.isBlank()) return null
        val map = mutableMapOf<String, String>()
        body.split("&").forEach { part ->
            val idx = part.indexOf(':')
            if (idx > 0) {
                val key = normalizeKey(part.substring(0, idx))
                val value = percentDecodePreservePlus(part.substring(idx + 1))
                map[key] = value
            }
        }
        val server = map["server"]?.let { extractBaseServerUrl(it) } ?: return null
        val user = map["user"].orEmpty()
        val pass = map["password"].orEmpty()
        return if (user.isNotBlank() && pass.isNotBlank()) {
            KodQrPayload.CredentialsLogin(server, user, pass)
        } else {
            KodQrPayload.ServerOnly(server)
        }
    }

    private fun parseCustomScheme(raw: String, depth: Int): KodQrPayload? {
        val afterScheme = raw.substringAfter("://", "")
        if (afterScheme.isBlank()) return null

        // Support kodbox://<base64> or kodbox://login?server=...&token=...
        if (!afterScheme.contains("?") && !afterScheme.contains("=") && depth < 2) {
            tryDecodeBase64Payload(afterScheme.trim('/'))?.let { decoded ->
                val nested = parseInternal(decoded, depth + 1)
                if (nested !is KodQrPayload.Unsupported) return nested
            }
        }

        val query = raw.substringAfter("?", "")
        val params = if (query.isNotBlank()) parseRawQueryParams(query) else emptyMap()
        val serverRaw = urlKeys.firstNotNullOfOrNull { params[it] }
            ?: afterScheme.substringBefore("?").takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        val server = extractBaseServerUrl(serverRaw) ?: return null

        val token = extractTokenFromNormalizedMap(params)
        if (!token.isNullOrBlank()) {
            return KodQrPayload.TokenLogin(server, token)
        }

        val user = params["username"] ?: params["name"] ?: params["user"] ?: params["account"]
        val pwd = params["password"] ?: params["pwd"] ?: params["pass"]
        if (!user.isNullOrBlank() && !pwd.isNullOrBlank()) {
            return KodQrPayload.CredentialsLogin(server, user, pwd)
        }

        if (serverRaw.contains("?") || serverRaw.contains("#")) {
            val nested = parseHttpUrlPayload(serverRaw, depth + 1)
            if (nested !is KodQrPayload.Unsupported) return nested
        }

        return KodQrPayload.ServerOnly(server)
    }

    private fun parseHttpUrlPayload(raw: String, depth: Int): KodQrPayload {
        val parsed = raw.toHttpUrlOrNull()
            ?: return KodQrPayload.Unsupported(raw, "二维码中的网址格式无效")

        // Collect all query & fragment params with normalized keys (preserving literal '+')
        val params = linkedMapOf<String, String>()
        val rawQuery = parsed.encodedQuery.orEmpty()
        if (rawQuery.isNotBlank()) {
            params.putAll(parseRawQueryParams(rawQuery))
        }
        val rawFragment = parsed.encodedFragment.orEmpty()
        if (rawFragment.contains("?")) {
            params.putAll(parseRawQueryParams(rawFragment.substringAfter("?")))
        } else if (rawFragment.contains("=")) {
            params.putAll(parseRawQueryParams(rawFragment))
        }

        // Check if this is a wrapper/download URL embedding the real self-hosted server in a query param
        val embeddedServerCandidate = urlKeys.firstNotNullOfOrNull { key ->
            params[key]?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }
        val baseServer = if (embeddedServerCandidate != null) {
            extractBaseServerUrl(embeddedServerCandidate) ?: buildBaseServerUrl(parsed.scheme, parsed.host, parsed.port, parsed.encodedPath)
        } else {
            buildBaseServerUrl(parsed.scheme, parsed.host, parsed.port, parsed.encodedPath)
        }

        // 1. Direct token in query or fragment params
        val token = extractTokenFromNormalizedMap(params)
        if (!token.isNullOrBlank()) {
            return KodQrPayload.TokenLogin(baseServer, token)
        }

        // 2. Hash-path token (e.g. https://host/#appLogin/<token> or https://host/#login/<token>)
        val fragmentPathToken = extractTokenFromFragmentPath(rawFragment)
        if (!fragmentPathToken.isNullOrBlank()) {
            return KodQrPayload.TokenLogin(baseServer, fragmentPathToken)
        }

        // 3. Base64-encoded JSON/query/URL inside any query parameter or fragment
        if (depth < 2) {
            for (k in encodedContainerKeys) {
                val encodedVal = params[k] ?: continue
                val decoded = tryDecodeBase64Payload(encodedVal) ?: continue
                val nested = parseInternal(decoded, depth + 1)
                if (nested is KodQrPayload.TokenLogin || nested is KodQrPayload.CredentialsLogin || nested is KodQrPayload.AuthUrl) {
                    return nested
                }
            }
            if (rawFragment.length >= 16 && !rawFragment.contains("/")) {
                tryDecodeBase64Payload(rawFragment)?.let { decoded ->
                    val nested = parseInternal(decoded, depth + 1)
                    if (nested is KodQrPayload.TokenLogin || nested is KodQrPayload.CredentialsLogin || nested is KodQrPayload.AuthUrl) {
                        return nested
                    }
                }
            }
        }

        // 4. Direct credentials in query/fragment
        val user = params["username"] ?: params["name"] ?: params["user"] ?: params["account"]
        val pwd = params["password"] ?: params["pwd"] ?: params["pass"]
        if (!user.isNullOrBlank() && !pwd.isNullOrBlank()) {
            return KodQrPayload.CredentialsLogin(baseServer, user, pwd)
        }

        // 5. Embedded URL parameter itself may be an auth callback URL
        if (depth < 2 && embeddedServerCandidate != null && (embeddedServerCandidate.contains("?") || embeddedServerCandidate.contains("#"))) {
            val nested = parseHttpUrlPayload(embeddedServerCandidate, depth + 1)
            if (nested !is KodQrPayload.Unsupported && nested !is KodQrPayload.ServerOnly) {
                return nested
            }
        }

        // 6. Any HTTP(S) URL carrying query params, non-root route path, or non-trivial fragment
        // should be attempted as a dynamic AuthUrl exchange instead of prematurely discarding it!
        val cleanPath = parsed.encodedPath.trimEnd('/')
        val hasNonRootPath = cleanPath.isNotEmpty() && !cleanPath.equals("/index.php", ignoreCase = true)
        val hasQuery = rawQuery.isNotBlank()
        val cleanFragment = rawFragment.trim().lowercase()
        val hasNonTrivialFragment = cleanFragment !in trivialFragments

        if (hasQuery || hasNonRootPath || hasNonTrivialFragment) {
            return KodQrPayload.AuthUrl(serverUrl = baseServer, fullUrl = raw)
        }

        return KodQrPayload.ServerOnly(baseServer)
    }

    private fun extractTokenFromNormalizedMap(params: Map<String, String>): String? {
        for (key in primaryTokenKeys) {
            val v = params[key]?.trim()
            if (!v.isNullOrBlank() && v.length >= 6 && !v.equals("null", true) && !v.equals("undefined", true)) {
                return v
            }
        }
        for (key in shortTokenKeys) {
            val v = params[key]?.trim()
            if (!v.isNullOrBlank() && v.length >= 16 && looksLikeTokenString(v)) {
                return v
            }
        }
        return null
    }

    private fun extractTokenFromFragmentPath(rawFragment: String): String? {
        if (rawFragment.isBlank()) return null
        val cleaned = rawFragment.trimStart('#', '/')
        val regex = Regex("""^(?:appLogin|login|qrLogin|auth|token|user/login)[/:]([A-Za-z0-9_\-+=.]{16,})$""", RegexOption.IGNORE_CASE)
        return regex.find(cleaned)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeTokenString(value: String): Boolean {
        if (value.contains(" ") || value.startsWith("http://", true) || value.startsWith("https://", true)) return false
        return value.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '+' || ch == '/' || ch == '=' || ch == '.' }
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace("_", "").replace("-", "")

    private fun extractBaseServerUrl(candidate: String): String? {
        val normalized = KodFormatters.normalizeServerUrl(candidate).getOrNull() ?: return null
        val parsed = normalized.toHttpUrlOrNull() ?: return normalized
        return buildBaseServerUrl(parsed.scheme, parsed.host, parsed.port, parsed.encodedPath)
    }

    private fun buildBaseServerUrl(scheme: String, host: String, port: Int, encodedPath: String): String {
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        val authority = if (defaultPort) host else "$host:$port"
        // Strip index.php or API/QR/short-link routes to recover the clean drive root path
        val cleanPath = encodedPath
            .substringBefore("/index.php")
            .substringBefore("/api")
            .substringBefore("/s/")
            .substringBefore("/qr/")
            .substringBefore("/user/")
            .trimEnd('/')
        return if (cleanPath.isEmpty() || cleanPath == "/") {
            "$scheme://$authority"
        } else {
            "$scheme://$authority$cleanPath"
        }
    }

    /**
     * Parses query key-value pairs while strictly preserving literal '+' characters in tokens
     * and normalizing keys (case + underscore insensitive).
     */
    private fun parseRawQueryParams(query: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        query.split("&").forEach { pair ->
            if (pair.contains("=")) {
                val rawKey = percentDecodePreservePlus(pair.substringBefore("="))
                val normKey = normalizeKey(rawKey)
                val value = percentDecodePreservePlus(pair.substringAfter("=")).trim()
                if (normKey.isNotBlank() && value.isNotBlank()) {
                    map[normKey] = value
                }
            }
        }
        return map
    }

    /**
     * Decodes percent-encoded UTF-8 sequences (`%XX`) without converting `+` to a space,
     * preventing token corruption when tokens contain literal `+`.
     */
    internal fun percentDecodePreservePlus(input: String): String {
        if (!input.contains('%')) return input
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hi = Character.digit(input[i + 1], 16)
                val lo = Character.digit(input[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun tryDecodeBase64Payload(raw: String): String? {
        val cleaned = raw.trim()
            .replace('-', '+')
            .replace('_', '/')
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        val decoded = padded.decodeBase64()?.utf8()?.trim() ?: return null
        return if (decoded.startsWith("{") ||
            decoded.startsWith("http://", true) ||
            decoded.startsWith("https://", true) ||
            decoded.startsWith("kod", true) ||
            decoded.startsWith("nc://", true)
        ) {
            decoded
        } else {
            null
        }
    }

    private fun looksLikeHost(raw: String): Boolean {
        if (raw.contains(" ") || raw.contains("\n")) return false
        return raw.contains(".") || raw.startsWith("localhost", ignoreCase = true)
    }
}
