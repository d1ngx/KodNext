package com.example

import com.example.data.KodBoxApiClient
import com.example.data.KodOAuthDiscovery
import com.example.data.KodPluginRegistry
import com.example.ui.components.DocumentGlyphStyle
import com.example.ui.components.resolveDocumentBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV008UiPluginOAuthTest {

    @Test
    fun parseSafeIntentSchemeMeta_allowsQqClientIntentAndExtractsFallback() {
        val qqIntent = "intent://ptlogin/qlogin?schemacallback=https%3A%2F%2Fgraph.qq.com#Intent;scheme=wtloginmqq;package=com.tencent.mobileqq;S.browser_fallback_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Fshow;end"
        val meta = KodOAuthDiscovery.parseSafeIntentSchemeMeta(qqIntent)
        assertNotNull(meta)
        assertEquals("wtloginmqq", meta!!.scheme)
        assertEquals("com.tencent.mobileqq", meta.targetPackage)
        assertEquals("https://graph.qq.com/oauth2.0/show", meta.browserFallbackUrl)
    }

    @Test
    fun parseSafeIntentSchemeMeta_blocksUntrustedPackageAndUnsafeScheme() {
        val evilPkg = "intent://ptlogin/qlogin#Intent;scheme=wtloginmqq;package=com.evil.stealer;end"
        assertNull(KodOAuthDiscovery.parseSafeIntentSchemeMeta(evilPkg))

        val evilScheme = "intent://run#Intent;scheme=file;package=com.tencent.mobileqq;end"
        assertNull(KodOAuthDiscovery.parseSafeIntentSchemeMeta(evilScheme))

        val jsFallback = "intent://ptlogin/qlogin#Intent;scheme=wtloginmqq;package=com.tencent.mobileqq;S.browser_fallback_url=javascript%3Aalert(1);end"
        val meta = KodOAuthDiscovery.parseSafeIntentSchemeMeta(jsFallback)
        assertNotNull(meta)
        assertEquals("", meta!!.browserFallbackUrl)
    }

    @Test
    fun upgradeRedirectToHttpsIfNeeded_upgradesOnlySameHostOnHttpsServer() {
        val server = "https://box.example.com"
        assertEquals(
            "https://box.example.com/plugins/adminer/adminer/",
            KodBoxApiClient.upgradeRedirectToHttpsIfNeeded("http://box.example.com/plugins/adminer/adminer/", server)
        )
        assertEquals(
            "http://other.example.org/foo",
            KodBoxApiClient.upgradeRedirectToHttpsIfNeeded("http://other.example.org/foo", server)
        )
        assertEquals(
            "http://local.lan/plugins/adminer/",
            KodBoxApiClient.upgradeRedirectToHttpsIfNeeded("http://local.lan/plugins/adminer/", "http://local.lan")
        )
    }

    @Test
    fun brokenAdminPlugins_areExcludedFromWorkspaceAppHub() {
        val server = "https://box.example.com"
        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("$server/plugins/webConsole/console/", server))
        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("$server/console/index.php", server))
        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("$server/plugins/adminer/adminer/", server))

        val js = """
            /* [console/static/main.js] */
            Events.bind('main.menu.loadBefore', function(listData) {
                listData['console'] = { name: 'Web 终端', url: '?plugin/console/index' };
            });
            /* [adminer/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['adminer'] = { name: 'Adminer', appUrl: 'https://box.example.com/plugins/adminer/adminer/' };
            });
            /* [drawio/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['drawio'] = { name: 'Drawio', appUrl: '?plugin/drawio/index' };
            });
        """.trimIndent()
        val apps = KodPluginRegistry.parseWorkspaceAppsFromJs(js, server)
        assertNull(apps.find { it.id.equals("console", ignoreCase = true) })
        assertNull(apps.find { it.id.equals("adminer", ignoreCase = true) })
        assertNotNull(apps.find { it.id.equals("drawio", ignoreCase = true) })
    }

    @Test
    fun resolveDocumentBadge_coversMultiFormatOfficeAndDocumentTypes() {
        val docx = resolveDocumentBadge("docx")
        assertNotNull(docx)
        assertEquals("W", docx!!.emblem)
        assertEquals("DOCX", docx.formatTag)
        assertEquals(DocumentGlyphStyle.WORD_LINES, docx.style)

        val wps = resolveDocumentBadge("wps")
        assertEquals("WPS", wps?.formatTag)
        assertEquals("W", wps?.emblem)

        val xlsx = resolveDocumentBadge("xlsx")
        assertEquals("X", xlsx?.emblem)
        assertEquals("XLSX", xlsx?.formatTag)
        assertEquals(DocumentGlyphStyle.SPREADSHEET_GRID, xlsx?.style)

        val csv = resolveDocumentBadge("csv")
        assertEquals("CSV", csv?.formatTag)

        val et = resolveDocumentBadge("et")
        assertEquals("ET", et?.formatTag)

        val pptx = resolveDocumentBadge("pptx")
        assertEquals("P", pptx?.emblem)
        assertEquals("PPTX", pptx?.formatTag)
        assertEquals(DocumentGlyphStyle.SLIDE_DECK, pptx?.style)

        val dps = resolveDocumentBadge("dps")
        assertEquals("DPS", dps?.formatTag)

        val ofd = resolveDocumentBadge("ofd")
        assertEquals("OFD", ofd?.formatTag)
        assertEquals(DocumentGlyphStyle.PDF_DOCUMENT, ofd?.style)

        val vsdx = resolveDocumentBadge("vsdx")
        assertEquals("V", vsdx?.emblem)
        assertEquals("VSDX", vsdx?.formatTag)

        assertNull(resolveDocumentBadge("mp4"))
        assertNull(resolveDocumentBadge("jpg"))
    }
}
