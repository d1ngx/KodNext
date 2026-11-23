package com.example

import com.example.data.KodAppUpdateChecker
import com.example.data.KodBoxApiClient
import com.example.data.KodPluginRegistry
import com.example.model.KodAccount
import com.example.model.KodFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodThumbnailsPluginsUpdateTest {

    private val demoAccount = KodAccount(
        id = "acc-1",
        serverUrl = "https://box.example.com",
        username = "admin",
        nickName = "管理员",
        accessToken = "tok_12345"
    )

    @Test
    fun supportsThumbnail_coversImagesVideosPdfsOfficeAndDesignFiles() {
        assertTrue(KodFileItem(path = "{source:1}/a.jpg", name = "a.jpg", isFolder = false, ext = "jpg").supportsThumbnail)
        assertTrue(KodFileItem(path = "{source:1}/b.pdf", name = "b.pdf", isFolder = false, ext = "pdf").supportsThumbnail)
        assertTrue(KodFileItem(path = "{source:1}/c.mp4", name = "c.mp4", isFolder = false, ext = "mp4").supportsThumbnail)
        assertTrue(KodFileItem(path = "{source:1}/d.docx", name = "d.docx", isFolder = false, ext = "docx").supportsThumbnail)
        assertTrue(KodFileItem(path = "{source:1}/e.psd", name = "e.psd", isFolder = false, ext = "psd").supportsThumbnail)
        assertTrue(
            KodFileItem(
                path = "{source:1}/custom.bin",
                name = "custom.bin",
                isFolder = false,
                ext = "bin",
                fileThumb = "/?plugin/fileThumb/cover&id=1"
            ).supportsThumbnail
        )
        assertFalse(KodFileItem(path = "{source:1}/dir/", name = "dir", isFolder = true).supportsThumbnail)
        assertFalse(KodFileItem(path = "{source:1}/notes.txt", name = "notes.txt", isFolder = false, ext = "txt").supportsThumbnail)
    }

    @Test
    fun buildThumbnailUrl_usesServerFileThumbAndFallsBackToKodBoxThumbQuery() {
        val client = KodBoxApiClient()

        val withRelativeThumb = KodFileItem(
            path = "{source:1}/report.pdf",
            name = "report.pdf",
            isFolder = false,
            ext = "pdf",
            fileThumb = "./?explorer/index/fileOut&path=abc&width=250"
        )
        val resolved = client.buildThumbnailUrl(demoAccount, withRelativeThumb)
        assertTrue(resolved.startsWith("https://box.example.com/?explorer/index/fileOut"))
        assertTrue(resolved.contains("accessToken=tok_12345"))

        val videoWithoutServerThumb = KodFileItem(
            path = "{source:1}/demo.mp4",
            name = "demo.mp4",
            isFolder = false,
            ext = "mp4",
            modifyTime = 1700000000L
        )
        val fallbackUrl = client.buildThumbnailUrl(demoAccount, videoWithoutServerThumb)
        assertTrue(fallbackUrl.contains("explorer/index/fileOut"))
        assertTrue(fallbackUrl.contains("width=250"))
        assertTrue(fallbackUrl.contains("type=image"))
        assertTrue(fallbackUrl.contains("etag=1700000000"))
        assertTrue(fallbackUrl.contains("accessToken=tok_12345"))
    }

    @Test
    fun parseWorkspaceAppsFromJs_skipsSvgIconsAndIgnoresExternalAdminerUrls() {
        val js = """
            /* [minder/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['minder'] = {
                    name: '思绪导图',
                    icon: 'https://box.example.com/plugins/minder/static/images/icon.svg'
                };
            });
            /* [excalidraw/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['excalidraw'] = {
                    name: '白板',
                    iconFile: './plugins/excalidraw/static/images/logo.svg?v=2'
                };
            });
            /* [svgEditor/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                listData['svgEditor'] = {
                    name: 'SVG编辑器',
                    icon: '/plugins/svgEditor/static/images/icon.svg'
                };
            });
            /* [adminer/static/main.js] */
            Events.bind('explorer.lightApp.load', function(listData) {
                var homepage = { url: 'https://www.adminer.org/' };
                listData['adminer'] = {
                    name: 'Adminer',
                    appUrl: 'https://box.example.com/plugins/adminer/adminer/',
                    icon: 'https://box.example.com/plugins/adminer/static/images/icon.png'
                };
            });
        """.trimIndent()

        val apps = KodPluginRegistry.parseWorkspaceAppsFromJs(js, "https://box.example.com")
        val byId = apps.associateBy { it.id.lowercase() }

        assertEquals("", byId["minder"]?.iconUrl)
        assertEquals("", byId["excalidraw"]?.iconUrl)
        assertEquals("", byId["svgeditor"]?.iconUrl)

        // Problematic standalone PHP plugins like adminer must not be surfaced in the App Hub
        assertFalse(byId.containsKey("adminer"))
    }

    @Test
    fun appUpdateChecker_buildsManifestCandidatesAndNormalizesUrls() {
        val candidates = KodAppUpdateChecker.buildCandidateManifestUrls("updates.example.com/kodnext")
        assertEquals(
            listOf(
                "https://updates.example.com/kodnext/version.json",
                "https://updates.example.com/kodnext/update.json",
                "https://updates.example.com/kodnext"
            ),
            candidates
        )
        assertEquals(
            listOf("https://updates.example.com/custom.json"),
            KodAppUpdateChecker.buildCandidateManifestUrls("https://updates.example.com/custom.json")
        )
        assertEquals(
            listOf("https://api.github.com/repos/org/kodnext/releases/latest"),
            KodAppUpdateChecker.buildCandidateManifestUrls("https://api.github.com/repos/org/kodnext/releases/latest")
        )
    }

    @Test
    fun appUpdateChecker_comparesVersionsAccurately() {
        assertTrue(KodAppUpdateChecker.isNewerVersion("0.05", 17, "0.06", 18))
        assertTrue(KodAppUpdateChecker.isNewerVersion("0.05", 17, "0.06", 0))
        assertTrue(KodAppUpdateChecker.isNewerVersion("0.09", 21, "0.10", 0))
        assertFalse(KodAppUpdateChecker.isNewerVersion("0.05", 17, "0.05", 17))
        assertFalse(KodAppUpdateChecker.isNewerVersion("0.05", 17, "0.04", 16))
        assertTrue(KodAppUpdateChecker.isNewerVersion("0.05", 17, "0.05", 18))
    }

    @Test
    fun appUpdateChecker_resolvesBuildTimeFixedUpdateUrl() {
        assertEquals("", KodAppUpdateChecker.resolveFixedUpdateUrl(""))
        assertEquals("", KodAppUpdateChecker.resolveFixedUpdateUrl("   "))
        assertEquals(
            "https://updates.example.com/kodnext/version.json",
            KodAppUpdateChecker.resolveFixedUpdateUrl("  https://updates.example.com/kodnext/version.json  ")
        )
        assertEquals(
            "https://updates.example.com/kodnext",
            KodAppUpdateChecker.resolveFixedUpdateUrl("updates.example.com/kodnext")
        )
    }
}
