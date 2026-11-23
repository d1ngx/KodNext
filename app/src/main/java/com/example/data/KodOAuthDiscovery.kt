package com.example.data

import com.example.model.KodOAuthProvider
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Pure-Kotlin discovery and URL-validation engine for KodBox third-party OAuth / SSO login providers.
 *
 * Supports detecting enabled providers from BOTH:
 * 1. `/?user/view/options` or `/?user/index/init` JSON (`regist.loginWith`, `oauth`, `thirdLogin`,
 *    `openLogin`, `pluginList`, including KodBox's JSON-stringified `regist` field).
 * 2. Unauthenticated `/?user/view/plugins` JS hook bundle (`plugins/oauth`, `oauth2`, `oidc`,
 *    `dingtalk`, `wework`, `feishu`, `cas`, `sso`).
 */
object KodOAuthDiscovery {

    private data class ProviderSpec(
        val canonicalId: String,
        val displayName: String,
        val priority: Int,
        val aliases: Set<String>
    )

    private val knownSpecs: List<ProviderSpec> = listOf(
        ProviderSpec(
            canonicalId = "weixin",
            displayName = "微信",
            priority = 100,
            aliases = setOf("weixin", "wechat", "wx", "weixinopen", "weixinmp", "wechatopen", "wxopen", "wxmp")
        ),
        ProviderSpec(
            canonicalId = "qq",
            displayName = "QQ",
            priority = 95,
            aliases = setOf("qq", "qqconnect", "tencentqq")
        ),
        ProviderSpec(
            canonicalId = "wework",
            displayName = "企业微信",
            priority = 90,
            aliases = setOf("wework", "wxwork", "qywechat", "qywx", "wechatwork", "workwechat", "weixinwork")
        ),
        ProviderSpec(
            canonicalId = "dingtalk",
            displayName = "钉钉",
            priority = 85,
            aliases = setOf("dingtalk", "dingding", "dd")
        ),
        ProviderSpec(
            canonicalId = "feishu",
            displayName = "飞书",
            priority = 80,
            aliases = setOf("feishu", "lark")
        ),
        ProviderSpec(
            canonicalId = "github",
            displayName = "GitHub",
            priority = 75,
            aliases = setOf("github")
        ),
        ProviderSpec(
            canonicalId = "gitee",
            displayName = "Gitee",
            priority = 70,
            aliases = setOf("gitee")
        ),
        ProviderSpec(
            canonicalId = "weibo",
            displayName = "微博",
            priority = 65,
            aliases = setOf("weibo", "sina", "sinaweibo")
        ),
        ProviderSpec(
            canonicalId = "oidc",
            displayName = "OIDC 登录",
            priority = 60,
            aliases = setOf("oidc", "openid", "openidconnect")
        ),
        ProviderSpec(
            canonicalId = "oauth2",
            displayName = "OAuth 登录",
            priority = 55,
            aliases = setOf("oauth2", "oauth", "sso", "cas", "saml")
        )
    )

    private val aliasToSpec: Map<String, ProviderSpec> = buildMap {
        knownSpecs.forEach { spec ->
            spec.aliases.forEach { alias -> put(alias, spec) }
        }
    }

    private val allowedExternalAppSchemes = setOf(
        "weixin",
        "weixinul",
        "wechat",
        "wtloginmqq",
        "wtloginmqq2",
        "wtlogintim",
        "mqqapi",
        "mqqwpa",
        "mqq",
        "mqqopensdkapi",
        "mqqopensdkapiv2",
        "mqqopensdkapiv3",
        "mqqopensdkbiz",
        "mqqopensdkminiapp",
        "tim",
        "timapi",
        "timopensdkapi",
        "dingtalk",
        "lark",
        "feishu",
        "wxwork",
        "wework"
    )

    private val allowedNativeOAuthPackages = setOf(
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.mm",
        "com.tencent.wework",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.ss.android.lark.kami"
    )

    data class SafeIntentSchemeMeta(
        val scheme: String,
        val targetPackage: String,
        val browserFallbackUrl: String
    )

    fun discover(
        optionsRawJson: String,
        pluginsJsBundle: String,
        normalizedServerUrl: String
    ): List<KodOAuthProvider> {
        val base = normalizedServerUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()

        val fromOptions = parseOAuthFromOptionsJson(optionsRawJson, base)
        val fromPlugins = parseOAuthFromPluginsJs(pluginsJsBundle, base)
        return mergeProviders(fromOptions, fromPlugins, base)
    }

    /**
     * Extracts enabled OAuth providers from `user/view/options` / `user/index/init` raw JSON text.
     * Implemented in pure Kotlin so it runs identically on Android and pure JVM unit tests,
     * and handles KodBox's double-encoded JSON strings (e.g. `"regist":"{\"loginWith\":\"qq,weixin\"}"`).
     */
    fun parseOAuthFromOptionsJson(rawJson: String, normalizedServerUrl: String): List<KodOAuthProvider> {
        if (rawJson.isBlank()) return emptyList()
        val base = normalizedServerUrl.trim().trimEnd('/')
        // Unescape stringified nested JSON blocks like `"regist":"{\"loginWith\":\"qq,weixin\"}"`
        val normalized = rawJson
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val found = linkedMapOf<String, KodOAuthProvider>()

        fun addProviderId(rawToken: String, explicitUrl: String = "", explicitName: String = "") {
            val spec = matchKnownSpec(rawToken) ?: return
            val existing = found[spec.canonicalId]
            val resolvedUrl = resolveSafeServerAuthUrl(explicitUrl, base)
                .ifBlank { existing?.authUrl.orEmpty() }
                .ifBlank { buildDefaultAuthUrl(base, spec.canonicalId) }
            val label = explicitName.trim().ifBlank { existing?.displayName ?: spec.displayName }
            found[spec.canonicalId] = KodOAuthProvider(
                id = spec.canonicalId,
                displayName = label,
                authUrl = resolvedUrl,
                fallbackLoginUrl = "$base/#user/login"
            )
        }

        // 1. String list values: "loginWith": "qq,weixin,github" or "thirdLogin": "qq,weixin"
        val stringListKeys = listOf(
            "loginWith", "login_with", "thirdLogin", "third_login",
            "oauthLogin", "oauth_login", "oauthList", "oauth_list",
            "openLogin", "open_login", "bindWith", "bind_with", "oauthType"
        )
        val stringListPattern = Regex(
            """"(?:${stringListKeys.joinToString("|")})"\s*:\s*"([^"]{1,300})"""",
            RegexOption.IGNORE_CASE
        )
        stringListPattern.findAll(normalized).forEach { match ->
            val rawList = match.groupValues[1]
            rawList.split(',', '|', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { token -> addProviderId(token) }
        }

        // 2. JSON array values: "loginWith": ["qq", "weixin"] or "oauth": ["qq", "github"]
        val arrayKeys = stringListKeys + listOf("oauth")
        val arrayPattern = Regex(
            """"(?:${arrayKeys.joinToString("|")})"\s*:\s*\[([^\]]{1,600})\]""",
            RegexOption.IGNORE_CASE
        )
        arrayPattern.findAll(normalized).forEach { match ->
            val inner = match.groupValues[1]
            Regex(""""([A-Za-z0-9_\-]{2,24})"""").findAll(inner).forEach { item ->
                addProviderId(item.groupValues[1])
            }
        }

        // 3. Nested object maps: "oauth": {"qq": 1, "weixin": true, "github": {"open": 1, "url": "..."}}
        val oauthBlockPattern = Regex(
            """"(?:oauth|oauthConfig|thirdLogin|openLogin|loginWith)"\s*:\s*\{([^{}]{1,800})\}""",
            RegexOption.IGNORE_CASE
        )
        oauthBlockPattern.findAll(normalized).forEach { match ->
            val body = match.groupValues[1]
            Regex(""""([A-Za-z0-9_\-]{2,24})"\s*:\s*(true|"1"|1|"true"|"on"|"yes")""", RegexOption.IGNORE_CASE)
                .findAll(body)
                .forEach { entry ->
                    addProviderId(entry.groupValues[1])
                }
        }

        // 4. Explicit boolean/flag for oauth enabled inside regist/settings (e.g. "oauth": 1 or "openOauth": 1)
        val genericOAuthEnabled = Regex(
            """"(?:openOauth|oauthEnable|oauthEnabled|enableOauth)"\s*:\s*(true|"1"|1|"true")""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalized)
        if (genericOAuthEnabled && found.isEmpty()) {
            addProviderId("oauth2", explicitUrl = "$base/#user/login", explicitName = "第三方 OAuth")
        }

        // 5. Inspect pluginList/plugins entries for enabled enterprise SSO/OAuth plugins
        val pluginStatusPattern = Regex(
            """"(oauth|oauth2|oidc|dingtalk|wework|wxwork|qywechat|feishu|lark|cas|sso)"\s*:\s*\{[^{}]{0,400}"status"\s*:\s*(1|"1"|true)""",
            RegexOption.IGNORE_CASE
        )
        pluginStatusPattern.findAll(normalized).forEach { match ->
            val pluginName = match.groupValues[1]
            if (pluginName.equals("oauth", ignoreCase = true)) {
                // Only add generic entry if no specific providers (qq/weixin/etc.) were discovered yet
                if (found.isEmpty()) {
                    addProviderId("oauth2", explicitUrl = "$base/#user/login", explicitName = "第三方登录")
                }
            } else {
                addProviderId(pluginName, explicitUrl = "$base/?plugin/$pluginName/")
            }
        }

        return sortProviders(found.values)
    }

    /**
     * Extracts enabled OAuth / third-party login providers from KodBox's unauthenticated
     * `/?user/view/plugins` JavaScript bundle.
     */
    fun parseOAuthFromPluginsJs(jsBundle: String, normalizedServerUrl: String): List<KodOAuthProvider> {
        if (jsBundle.isBlank()) return emptyList()
        val base = normalizedServerUrl.trim().trimEnd('/')
        val blockMarker = Regex("""/\*\s*\[([^\]/]+)/[^\]]*\]\s*\*/""")
        val matches = blockMarker.findAll(jsBundle).toList()
        val found = linkedMapOf<String, KodOAuthProvider>()

        val oauthRoutePattern = Regex(
            """['"]((?:\./|/)?(?:index\.php)?\?(?:plugin/oauth[^'"]*|user/bind/oauth[^'"]*|plugin/(?:oauth2|oidc|dingtalk|wework|wxwork|feishu|cas|sso)[^'"]*))['"]""",
            RegexOption.IGNORE_CASE
        )

        for (i in matches.indices) {
            val pluginName = matches[i].groupValues[1].trim()
            val lowerPlugin = pluginName.lowercase(Locale.US)
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else jsBundle.length
            val block = jsBundle.substring(start, end)

            val isOAuthPlugin = lowerPlugin == "oauth" ||
                lowerPlugin.contains("oauth") ||
                lowerPlugin == "oidc" ||
                lowerPlugin == "dingtalk" ||
                lowerPlugin == "wework" ||
                lowerPlugin == "wxwork" ||
                lowerPlugin == "qywechat" ||
                lowerPlugin == "feishu" ||
                lowerPlugin == "lark" ||
                lowerPlugin == "cas" ||
                lowerPlugin == "sso"

            if (!isOAuthPlugin) continue

            // Discover explicit route in the JS block if present
            val discoveredRoute = oauthRoutePattern.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val hasPluginOAuthBind = discoveredRoute.contains("plugin/oauth", ignoreCase = true)

            if (lowerPlugin == "oauth") {
                // Parse provider lists declared inside plugins/oauth static JS (e.g. loginWith, bindList, typeList)
                val jsListPatterns = listOf(
                    Regex("""(?:loginWith|openList|bindList|thirdList|types|providers)\s*[:=]\s*['"]([^'"]{1,200})['"]""", RegexOption.IGNORE_CASE),
                    Regex("""(?:loginWith|openList|bindList|thirdList|types|providers)\s*[:=]\s*\[([^\]]{1,400})\]""", RegexOption.IGNORE_CASE)
                )
                val extractedIds = linkedSetOf<String>()
                jsListPatterns.forEach { regex ->
                    regex.findAll(block).forEach { m ->
                        Regex("""[A-Za-z0-9_\-]{2,20}""").findAll(m.groupValues[1]).forEach { tok ->
                            val spec = matchKnownSpec(tok.value)
                            if (spec != null && spec.canonicalId != "oauth2") {
                                extractedIds.add(spec.canonicalId)
                            }
                        }
                    }
                }
                // Also detect explicit type=qq / type=weixin references inside the oauth plugin block
                Regex("""[?&]type=([a-zA-Z0-9_\-]{2,20})""", RegexOption.IGNORE_CASE)
                    .findAll(block)
                    .forEach { m ->
                        matchKnownSpec(m.groupValues[1])?.let { spec ->
                            if (spec.canonicalId != "oauth2") extractedIds.add(spec.canonicalId)
                        }
                    }

                if (extractedIds.isNotEmpty()) {
                    extractedIds.forEach { id ->
                        val spec = aliasToSpec[id] ?: return@forEach
                        val url = if (hasPluginOAuthBind) {
                            "$base/?plugin/oauth/bind/index&type=${urlEncode(id)}"
                        } else {
                            buildDefaultAuthUrl(base, id)
                        }
                        found[id] = KodOAuthProvider(
                            id = id,
                            displayName = spec.displayName,
                            authUrl = url,
                            fallbackLoginUrl = "$base/#user/login"
                        )
                    }
                } else {
                    // The official `oauth` plugin is enabled on the server, even if provider keys are
                    // fetched dynamically inside the SPA — expose the generic OAuth entry safely.
                    val fallbackUrl = resolveSafeServerAuthUrl(discoveredRoute, base)
                        .ifBlank { "$base/#user/login" }
                    found["oauth2"] = KodOAuthProvider(
                        id = "oauth2",
                        displayName = "第三方登录",
                        authUrl = fallbackUrl,
                        fallbackLoginUrl = "$base/#user/login"
                    )
                }
            } else {
                val spec = matchKnownSpec(lowerPlugin) ?: ProviderSpec(
                    canonicalId = lowerPlugin,
                    displayName = pluginName,
                    priority = 50,
                    aliases = setOf(lowerPlugin)
                )
                val titleMatch = Regex("""(?:title|name|appName)\s*[:=]\s*['"]([^'"]{1,24})['"]""")
                    .find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val resolvedUrl = resolveSafeServerAuthUrl(discoveredRoute, base)
                    .ifBlank { "$base/?plugin/$pluginName/" }
                found[spec.canonicalId] = KodOAuthProvider(
                    id = spec.canonicalId,
                    displayName = titleMatch.ifBlank { spec.displayName },
                    authUrl = resolvedUrl,
                    fallbackLoginUrl = "$base/#user/login"
                )
            }
        }

        return sortProviders(found.values)
    }

    fun mergeProviders(
        fromOptions: List<KodOAuthProvider>,
        fromPlugins: List<KodOAuthProvider>,
        normalizedServerUrl: String
    ): List<KodOAuthProvider> {
        val merged = linkedMapOf<String, KodOAuthProvider>()
        fromOptions.forEach { p -> merged[p.id] = p }
        fromPlugins.forEach { p ->
            val existing = merged[p.id]
            if (existing == null) {
                merged[p.id] = p
            } else if (existing.authUrl.endsWith("#user/login") && !p.authUrl.endsWith("#user/login")) {
                merged[p.id] = p
            }
        }
        // If concrete providers (e.g. qq, weixin, github, dingtalk) exist, drop redundant generic "oauth2" placeholder
        // pointing only at #user/login so the UI stays crisp and focused.
        val hasSpecific = merged.keys.any { it != "oauth2" }
        if (hasSpecific) {
            val generic = merged["oauth2"]
            if (generic != null && (generic.authUrl.endsWith("#user/login") || generic.displayName == "第三方登录")) {
                merged.remove("oauth2")
            }
        }
        return sortProviders(merged.values)
    }

    fun buildDefaultAuthUrl(normalizedServerUrl: String, providerId: String): String {
        val base = normalizedServerUrl.trim().trimEnd('/')
        val cleanId = providerId.trim().lowercase(Locale.US)
        return when (cleanId) {
            "qq", "weixin", "github", "gitee", "weibo", "dingtalk", "wework", "feishu" ->
                "$base/?user/bind/oauth&type=${urlEncode(cleanId)}&action=login"
            "oidc", "oauth2" ->
                "$base/?plugin/$cleanId/"
            else ->
                "$base/?user/bind/oauth&type=${urlEncode(cleanId)}"
        }
    }

    /**
     * Strictly validates that an OAuth entry URL resolved from server metadata uses HTTP/HTTPS
     * and stays on the user's configured KodBox server host (preventing arbitrary open redirects
     * or unsafe schemes like `javascript:` / `file:` / `data:`).
     */
    fun resolveSafeServerAuthUrl(rawUrl: String, normalizedServerUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank() || trimmed.contains("{{") || trimmed.contains("}}")) return ""
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("data:") || lower.startsWith("content:")) {
            return ""
        }
        val base = normalizedServerUrl.trim().trimEnd('/')
        val resolved = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "$base$trimmed"
            trimmed.startsWith("./") -> "$base/${trimmed.removePrefix("./")}"
            trimmed.startsWith("?") || trimmed.startsWith("index.php?") -> "$base/$trimmed"
            else -> return ""
        }
        val expectedHost = extractHost(base)
        val resolvedHost = extractHost(resolved)
        if (expectedHost.isNotBlank() && resolvedHost.isNotBlank() && !expectedHost.equals(resolvedHost, ignoreCase = true)) {
            return ""
        }
        return resolved
    }

    /**
     * Checks whether a non-HTTP scheme encountered during an OAuth flow is an allowlisted
     * native social-login app handoff (e.g. WeChat, QQ, TIM, DingTalk, Feishu, WeWork).
     */
    fun isAllowedNativeOAuthAppScheme(scheme: String?): Boolean {
        val clean = scheme?.trim()?.lowercase(Locale.US).orEmpty()
        return clean in allowedExternalAppSchemes
    }

    /**
     * Checks whether a target package referenced by an `intent://` URI belongs to an allowlisted
     * native social-login client (QQ, TIM, WeChat, WeWork, DingTalk, Feishu/Lark).
     */
    fun isAllowedNativeOAuthPackage(packageName: String?): Boolean {
        val clean = packageName?.trim()?.lowercase(Locale.US).orEmpty()
        return clean in allowedNativeOAuthPackages
    }

    /**
     * Parses and validates an Android Chrome `intent://...#Intent;...;end` URI triggered by
     * mobile OAuth pages (notably QQ Connect / DingTalk / Feishu mobile web authorization).
     * Returns non-null ONLY when the inner scheme is allowlisted and any specified package is
     * also in the strict native OAuth client allowlist.
     */
    fun parseSafeIntentSchemeMeta(intentUri: String): SafeIntentSchemeMeta? {
        val trimmed = intentUri.trim()
        if (!trimmed.startsWith("intent://", ignoreCase = true)) return null
        val markerIdx = trimmed.indexOf("#Intent;", ignoreCase = true)
        if (markerIdx < 0) return null
        val paramsSegment = trimmed.substring(markerIdx + "#Intent;".length)
        var scheme = ""
        var pkg = ""
        var fallback = ""
        paramsSegment.split(";").forEach { rawToken ->
            val token = rawToken.trim()
            when {
                token.startsWith("scheme=", ignoreCase = true) ->
                    scheme = token.substringAfter("=").trim().lowercase(Locale.US)
                token.startsWith("package=", ignoreCase = true) ->
                    pkg = token.substringAfter("=").trim().lowercase(Locale.US)
                token.startsWith("S.browser_fallback_url=", ignoreCase = true) -> {
                    val encoded = token.substringAfter("=").trim()
                    val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                    if (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)) {
                        fallback = decoded
                    }
                }
            }
        }
        if (!isAllowedNativeOAuthAppScheme(scheme)) return null
        if (pkg.isNotBlank() && !isAllowedNativeOAuthPackage(pkg)) return null
        return SafeIntentSchemeMeta(
            scheme = scheme,
            targetPackage = pkg,
            browserFallbackUrl = fallback
        )
    }

    /**
     * Strips Android WebView markers (`; wv` and `Version/4.0`) so QQ Connect (`ptlogin2`)
     * and other mobile OAuth pages recognize the browser as standard Mobile Chrome and enable
     * one-tap native client handoff (`wtloginmqq://`, `mqqapi://`, `intent://`).
     */
    fun sanitizeMobileChromeUserAgent(rawUa: String): String {
        if (rawUa.isBlank()) return rawUa
        return rawUa
            .replace("; wv", "", ignoreCase = true)
            .replace(Regex("""Version/\d+(?:\.\d+)*\s*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /**
     * Ensures QQ Connect OAuth authorization URLs (`https://graph.qq.com/oauth2.0/authorize`)
     * request the mobile H5 page (`display=mobile`) so QQ's native one-tap login button is shown
     * instead of the desktop PC QR layout. Leaves all other URLs and parameters untouched.
     */
    fun ensureQqMobileAuthorizeUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return trimmed
        val host = extractHost(trimmed)
        if (host != "graph.qq.com") return trimmed
        val path = runCatching { URI(trimmed).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        if (!path.startsWith("/oauth2.0/authorize")) return trimmed

        val fragmentIdx = trimmed.indexOf('#')
        val basePart = if (fragmentIdx >= 0) trimmed.substring(0, fragmentIdx) else trimmed
        val fragmentPart = if (fragmentIdx >= 0) trimmed.substring(fragmentIdx) else ""

        val replacedPc = Regex("""([?&])display=pc(?=&|$)""", RegexOption.IGNORE_CASE)
        if (replacedPc.containsMatchIn(basePart)) {
            return replacedPc.replace(basePart, "$1display=mobile") + fragmentPart
        }
        val hasDisplay = Regex("""[?&]display=""", RegexOption.IGNORE_CASE).containsMatchIn(basePart)
        if (hasDisplay) return trimmed
        val sep = if (basePart.contains('?')) '&' else '?'
        return "${basePart}${sep}display=mobile$fragmentPart"
    }

    /**
     * Determines whether the current WebView URL has returned to the target KodBox server host
     * and is a candidate for completing OAuth session verification.
     */
    fun isTargetServerHost(currentUrl: String, normalizedServerUrl: String): Boolean {
        val currentHost = extractHost(currentUrl)
        val targetHost = extractHost(normalizedServerUrl)
        return currentHost.isNotBlank() && targetHost.isNotBlank() &&
            currentHost.equals(targetHost, ignoreCase = true)
    }

    private fun matchKnownSpec(rawToken: String): ProviderSpec? {
        val normalized = rawToken.trim().lowercase(Locale.US).replace("_", "").replace("-", "")
        if (normalized.isBlank()) return null
        return aliasToSpec[normalized]
    }

    private fun sortProviders(providers: Collection<KodOAuthProvider>): List<KodOAuthProvider> {
        return providers.sortedWith(
            compareByDescending<KodOAuthProvider> { aliasToSpec[it.id]?.priority ?: 10 }
                .thenBy { it.displayName }
        )
    }

    private fun extractHost(url: String): String {
        return runCatching { URI(url).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
    }

    private fun urlEncode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
