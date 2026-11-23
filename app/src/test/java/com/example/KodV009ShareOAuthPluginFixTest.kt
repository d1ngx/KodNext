package com.example

import com.example.data.KodBoxApiClient
import com.example.data.KodOAuthDiscovery
import com.example.data.KodPluginRegistry
import com.example.model.KodFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV009ShareOAuthPluginFixTest {

    @Test
    fun canShare_hidesShareButtonForEnterpriseDepartmentAndVirtualDirectories() {
        val enterpriseDir = KodFileItem(
            path = "{block:enterprise}/",
            name = "企业网盘",
            isFolder = true
        )
        val myDepartmentDir = KodFileItem(
            path = "{group:self}/",
            name = "我所在的部门",
            isFolder = true
        )
        val groupRootDir = KodFileItem(
            path = "{source:2}/",
            sourceId = "2",
            name = "研发部",
            isFolder = true,
            isSpecialDirectory = true
        )
        val recycleFile = KodFileItem(
            path = "{source:99}/doc.pdf",
            sourceId = "99",
            name = "doc.pdf",
            isFolder = false,
            isRecycleItem = true
        )
        val normalFile = KodFileItem(
            path = "{source:101}/report.docx",
            sourceId = "101",
            name = "report.docx",
            isFolder = false
        )
        val normalFolder = KodFileItem(
            path = "{source:102}/",
            sourceId = "102",
            name = "项目文档",
            isFolder = true
        )

        assertFalse(enterpriseDir.canShare)
        assertFalse(myDepartmentDir.canShare)
        assertFalse(groupRootDir.canShare)
        assertFalse(recycleFile.canShare)
        assertTrue(normalFile.canShare)
        assertTrue(normalFolder.canShare)
    }

    @Test
    fun sanitizeMobileChromeUserAgent_stripsBothWvAndVersion40Markers() {
        val rawAndroidWebViewUa =
            "Mozilla/5.0 (Linux; Android 16; PKG110 Build/BP2A.250605; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36"
        val sanitized = KodOAuthDiscovery.sanitizeMobileChromeUserAgent(rawAndroidWebViewUa)
        assertFalse(sanitized.contains("; wv"))
        assertFalse(sanitized.contains("Version/4.0"))
        assertTrue(sanitized.contains("Chrome/131.0.6778.200 Mobile Safari/537.36"))
    }

    @Test
    fun ensureQqMobileAuthorizeUrl_enforcesMobileDisplayOnlyOnGraphQqAuthorize() {
        val qqPcUrl = "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=101&display=pc&state=abc"
        assertEquals(
            "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=101&display=mobile&state=abc",
            KodOAuthDiscovery.ensureQqMobileAuthorizeUrl(qqPcUrl)
        )

        val qqNoDisplay = "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=101&state=abc"
        assertEquals(
            "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=101&state=abc&display=mobile",
            KodOAuthDiscovery.ensureQqMobileAuthorizeUrl(qqNoDisplay)
        )

        val wechatUrl = "https://open.weixin.qq.com/connect/qrconnect?appid=wx123&response_type=code#wechat_redirect"
        assertEquals(wechatUrl, KodOAuthDiscovery.ensureQqMobileAuthorizeUrl(wechatUrl))
    }

    @Test
    fun allowedOAuthSchemes_coverExtendedQqAndTimSchemes() {
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("wtloginmqq"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("wtloginmqq2"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("mqqopensdkapiv3"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("mqqopensdkbiz"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("tim"))
        assertTrue(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("weixin"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("javascript"))
        assertFalse(KodOAuthDiscovery.isAllowedNativeOAuthAppScheme("file"))
    }

    @Test
    fun parseWorkspaceAppsFromJs_excludesBrokenAdminAndAuthPluginsButKeepsHealthyWorkspaceApps() {
        val js = """
            /* [adminer/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['adminer'] = { name: 'Adminer', appUrl: '?plugin/adminer/index' };
            });
            /* [webConsole/static/main.js] */
            Events.bind('main.menu.loadBefore', function(listData) {
                listData['webConsole'] = { name: 'WebConsole', appUrl: '?plugin/webConsole/index' };
            });
            /* [mcp/static/main.js] */
            Events.bind('main.menu.loadBefore', function(listData) {
                listData['mcp'] = { name: 'MCP', appUrl: '?plugin/mcp/index' };
            });
            /* [project/static/main.js] */
            Events.bind('main.menu.loadBefore', function(listData) {
                Router.mapPage({ page: 'project' });
                listData['project'] = { name: '项目' };
            });
        """.trimIndent()
        val apps = KodPluginRegistry.parseWorkspaceAppsFromJs(js, "https://box.example.com")
        assertNull(apps.find { it.id.equals("adminer", ignoreCase = true) })
        assertNull(apps.find { it.id.equals("webConsole", ignoreCase = true) })
        assertNull(apps.find { it.id.equals("mcp", ignoreCase = true) })
        assertNotNull(apps.find { it.id.equals("project", ignoreCase = true) })
    }
}
