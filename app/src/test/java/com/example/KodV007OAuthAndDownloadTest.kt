package com.example

import com.example.data.KodFormatters
import com.example.data.KodOAuthDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV007OAuthAndDownloadTest {

    private val server = "https://cloud.example.com"

    @Test
    fun parseOptionsJson_handlesDoubleEscapedRegistLoginWith() {
        val raw = """
            {
              "code": true,
              "data": {
                "system": {
                  "settings": {
                    "systemName": "KodBox",
                    "regist": "{\"openRegist\":\"1\",\"loginWith\":\"qq,weixin,github\"}"
                  }
                }
              }
            }
        """.trimIndent()

        val providers = KodOAuthDiscovery.parseOAuthFromOptionsJson(raw, server)
        val ids = providers.map { it.id }
        assertEquals(listOf("weixin", "qq", "github"), ids)
        assertTrue(providers.first { it.id == "qq" }.authUrl.startsWith(server))
        assertEquals("微信", providers.first { it.id == "weixin" }.displayName)
        assertEquals("QQ", providers.first { it.id == "qq" }.displayName)
    }

    @Test
    fun parseOptionsJson_handlesArraysNestedMapsAndPluginList() {
        val raw = """
            {
              "data": {
                "regist": {
                  "loginWith": ["dingtalk", "qq"],
                  "oauth": {"weixin": true, "weibo": false}
                },
                "pluginList": {
                  "wework": {"status": 1},
                  "pdfjs": {"status": 1}
                }
              }
            }
        """.trimIndent()

        val providers = KodOAuthDiscovery.parseOAuthFromOptionsJson(raw, server)
        val ids = providers.map { it.id }
        assertTrue(ids.contains("weixin"))
        assertTrue(ids.contains("qq"))
        assertTrue(ids.contains("wework"))
        assertTrue(ids.contains("dingtalk"))
        assertFalse(ids.contains("weibo"))
    }

    @Test
    fun parseOptionsJson_returnsEmptyWhenOAuthDisabled() {
        val raw = """
            {
              "code": true,
              "data": {
                "system": {
                  "settings": {
                    "systemName": "PrivateCloud",
                    "needCheckCode": "0"
                  }
                }
              }
            }
        """.trimIndent()

        val providers = KodOAuthDiscovery.parseOAuthFromOptionsJson(raw, server)
        assertTrue(providers.isEmpty())
    }

    @Test
    fun parsePluginsJs_discoversOAuthPluginAndEnterpriseSso() {
        val js = """
            /* [pdfjs/static/main.js] */
            kodReady.push(function(){ console.log('pdfjs'); });
            /* [oauth/static/main.js] */
            var loginWith = "qq,weixin";
            var bindUrl = "./?plugin/oauth/bind/index&type=qq";
            /* [oidc/static/main.js] */
            var title = "统一身份认证";
            var entry = "./?plugin/oidc/login";
        """.trimIndent()

        val providers = KodOAuthDiscovery.parseOAuthFromPluginsJs(js, server)
        val byId = providers.associateBy { it.id }
        assertTrue(byId.containsKey("weixin"))
        assertTrue(byId.containsKey("qq"))
        assertTrue(byId.containsKey("oidc"))
        assertEquals("统一身份认证", byId["oidc"]?.displayName)
        assertEquals("$server/?plugin/oauth/bind/index&type=qq", byId["qq"]?.authUrl)
        assertEquals("$server/?plugin/oidc/login", byId["oidc"]?.authUrl)
    }

    @Test
    fun discover_mergesAndDropsGenericPlaceholderWhenSpecificProvidersExist() {
        val options = """{"data":{"regist":{"loginWith":"qq,weixin"}}}"""
        val pluginsJs = """
            /* [oauth/static/main.js] */
            kodReady.push(function(){});
        """.trimIndent()

        val providers = KodOAuthDiscovery.discover(options, pluginsJs, server)
        assertEquals(listOf("weixin", "qq"), providers.map { it.id })
    }

    @Test
    fun resolveSafeServerAuthUrl_blocksUnsafeSchemesAndOffHostUrls() {
        assertEquals("", KodOAuthDiscovery.resolveSafeServerAuthUrl("javascript:alert(1)", server))
        assertEquals("", KodOAuthDiscovery.resolveSafeServerAuthUrl("file:///etc/passwd", server))
        assertEquals("", KodOAuthDiscovery.resolveSafeServerAuthUrl("data:text/html,<script>", server))
        assertEquals("", KodOAuthDiscovery.resolveSafeServerAuthUrl("content://com.example/secret", server))
        assertEquals("", KodOAuthDiscovery.resolveSafeServerAuthUrl("https://evil.attacker.tld/?user/bind/oauth", server))

        assertEquals(
            "$server/?user/bind/oauth&type=qq",
            KodOAuthDiscovery.resolveSafeServerAuthUrl("?user/bind/oauth&type=qq", server)
        )
        assertEquals(
            "$server/index.php?plugin/oauth/bind",
            KodOAuthDiscovery.resolveSafeServerAuthUrl("/index.php?plugin/oauth/bind", server)
        )
        assertEquals(
            "$server/?plugin/oidc/",
            KodOAuthDiscovery.resolveSafeServerAuthUrl("$server/?plugin/oidc/", server)
        )
    }

    @Test
    fun isAllowedNativeOAuthAppScheme_enforcesStrictAllowlist() {
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("weixin"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("mqqapi"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("wtloginmqq"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("dingtalk"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("wxwork"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("lark"))

        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("javascript"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("file"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("content"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("data"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("intent"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("tel"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme(null))
    }

    @Test
    fun isTargetServerHost_matchesCaseInsensitively() {
        assertTrue(KodOAuthDiscovery.isTargetServerHost("https://CLOUD.EXAMPLE.COM/#desktop", server))
        assertFalse(KodOAuthDiscovery.isTargetServerHost("https://graph.qq.com/oauth2.0/authorize", server))
        assertFalse(KodOAuthDiscovery.isTargetServerHost("about:blank", server))
    }

    @Test
    fun mimeTypeForExtension_coversCommonDownloadedFileTypes() {
        assertEquals("application/pdf", KodFormatters.mimeTypeForExtension("pdf"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", KodFormatters.mimeTypeForExtension("docx"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", KodFormatters.mimeTypeForExtension("xlsx"))
        assertEquals("video/mp4", KodFormatters.mimeTypeForExtension("mp4"))
        assertEquals("video/quicktime", KodFormatters.mimeTypeForExtension("mov"))
        assertEquals("audio/mp4", KodFormatters.mimeTypeForExtension("m4a"))
        assertEquals("application/zip", KodFormatters.mimeTypeForExtension("zip"))
        assertEquals("application/x-7z-compressed", KodFormatters.mimeTypeForExtension("7z"))
        assertEquals("application/vnd.android.package-archive", KodFormatters.mimeTypeForExtension("apk"))
        assertEquals("application/epub+zip", KodFormatters.mimeTypeForExtension("epub"))
        assertEquals("text/plain", KodFormatters.mimeTypeForExtension("md"))
        assertEquals("*/*", KodFormatters.mimeTypeForExtension("unknownext123"))
    }
}
