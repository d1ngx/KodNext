package com.example

import com.example.data.KodBoxApiClient
import com.example.data.KodDirectoryCache
import com.example.data.KodPluginRegistry
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodSpace
import com.example.model.OfficePluginViewer
import com.example.model.PathListResult
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.ui.components.isKodBoxMainSpaUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodBoxApiClientUnitTest {

    private val sampleAccount = KodAccount(
        id = "test_acc",
        serverUrl = "https://cloud.example.com",
        username = "tester",
        nickName = "Tester",
        accessToken = "tok_123",
        csrfToken = "csrf_456",
        personalRootPath = "{source:17}/"
    )

    @Test
    fun kodBox171VirtualPathsMatchServerSpecification() {
        assertEquals("{userRencent}/", KodSpace.RECENT.virtualPath)
        assertEquals("{userFileType:doc}/", KodFileTypeCategory.DOC.virtualPath)
        assertEquals("{userFileType:image}/", KodFileTypeCategory.IMAGE.virtualPath)
        assertEquals("{userFileType:movie}/", KodFileTypeCategory.VIDEO.virtualPath)
        assertEquals("{userFileType:music}/", KodFileTypeCategory.MUSIC.virtualPath)
        assertEquals("{block:fileType}/", KodFileTypeCategory.ALL.virtualPath)
        assertEquals("{block:enterprise}/", KodSpace.ENTERPRISE.virtualPath)
        assertEquals("{shareToMe}/", KodSpace.SHARED_WITH_ME.virtualPath)
        assertEquals("{userFav}/", KodSpace.FAVORITES.virtualPath)
    }

    @Test
    fun primarySpacesAreStrictlyParallel() {
        val primaries = KodSpace.entries.filter { it.isPrimarySpace }
        assertEquals(
            listOf(KodSpace.PERSONAL, KodSpace.ENTERPRISE, KodSpace.FAVORITES, KodSpace.SHARED_WITH_ME),
            primaries
        )
    }

    @Test
    fun officeAudioVideoAndPdfClassificationWorks() {
        val docx = KodFileItem(path = "{source:1}/", name = "report.docx", isFolder = false, ext = "docx")
        val xlsx = KodFileItem(path = "{source:2}/", name = "sheet.xlsx", isFolder = false, ext = "xlsx")
        val pptx = KodFileItem(path = "{source:3}/", name = "deck.pptx", isFolder = false, ext = "pptx")
        val pdf = KodFileItem(path = "{source:4}/", name = "manual.pdf", isFolder = false, ext = "pdf")
        val mp4 = KodFileItem(path = "{source:5}/", name = "clip.mp4", isFolder = false, ext = "mp4")
        val mp3 = KodFileItem(path = "{source:6}/", name = "track.mp3", isFolder = false, ext = "mp3")
        val dwg = KodFileItem(path = "{source:7}/", name = "floorplan.dwg", isFolder = false, ext = "dwg")
        val epub = KodFileItem(path = "{source:8}/", name = "book.epub", isFolder = false, ext = "epub")
        val smm = KodFileItem(path = "{source:9}/", name = "map.smm", isFolder = false, ext = "smm")

        assertTrue(docx.isOfficeDocument)
        assertTrue(xlsx.isOfficeDocument)
        assertTrue(pptx.isOfficeDocument)
        assertFalse(docx.isEditableText)
        assertTrue(pdf.isPdf)
        assertTrue(mp4.isVideo)
        assertTrue(mp3.isAudio)
        assertTrue(dwg.isCadOr3d)
        assertTrue(epub.isEbook)
        assertTrue(smm.isDiagramOrMindMap)
    }

    @Test
    fun pluginRegistryParsesLiteralAndVariableExtensions() {
        val sampleJs = """
            /* [autoViewer/static/app/main.js] */
            var extAllow = 'dwg,dxf,stl,glb';
            kodApp.appList.push({name:'autoViewer',title:'3D/CAD 看图',ext:extAllow,sort:'400',callback:function(){core.openFile('index.php?plugin/autoViewer/');}});
            /* [epubReader/static/app/main.js] */
            kodApp.appList.push({name:'epubReader',title:'电子书阅读器',ext:'epub,mobi,azw3',sort:'200',callback:function(){core.openFile('?plugin/epubReader/');}});
            /* [drawio/static/app/main.js] */
            kodApp.appList.push({name:'drawio',title:'Drawio流程图',ext:'drawio,vsdx',sort:'200',callback:function(){core.openFile('?plugin/drawio/');}});
            /* [minder/static/app/main.js] */
            kodApp.appList.push({name:'minder',title:'思绪思维导图',ext:'smm,xmind',sort:'200',callback:function(){core.openFile('?plugin/minder/');}});
        """.trimIndent()

        val parsed = KodPluginRegistry.parsePluginsFromJs(sampleJs)
        assertEquals(4, parsed.size)
        assertEquals("autoViewer", KodPluginRegistry.matchPluginsForExtension("dwg", parsed).first().name)
        assertEquals("epubReader", KodPluginRegistry.matchPluginsForExtension("epub", parsed).first().name)
        assertEquals("drawio", KodPluginRegistry.matchPluginsForExtension("drawio", parsed).first().name)
        assertEquals("minder", KodPluginRegistry.matchPluginsForExtension("smm", parsed).first().name)
    }

    @Test
    fun workspacePluginDiscoveryFindsProjectKodImAndAdminTools() {
        val sampleBundle = """
            /* [project/static/app/main.js] */
            Events.bind('main.menu.loadBefore', function(){});
            Router.mapPage({page:'project',title:'项目'});
            /* [kodIM/static/app/main.js] */
            Events.bind('main.menu.loadBefore', function(){ var menuName='即时通讯'; });
            /* [HelpCenter/static/app/main.js] */
            Events.bind('main.menu.loadBefore', function(){ var menuName='帮助中心'; });
            /* [adminer/static/app/main.js] */
            Events.bind('explorer.lightApp.load', function(){ var name='Adminer'; });
            /* [epubReader/static/app/main.js] */
            kodApp.appList.push({name:'epubReader',title:'电子书',ext:'epub'});
        """.trimIndent()

        val apps = KodPluginRegistry.parseWorkspaceAppsFromJs(sampleBundle, "https://cloud.example.com")
        assertEquals(3, apps.size)

        val project = apps.first()
        assertEquals("project", project.id)
        assertEquals("https://cloud.example.com/#project", project.launchUrl)
        assertTrue(project.isRouterPage)
        assertFalse(project.isAdminOnly)

        val kodIm = apps.find { it.id == "kodIM" }
        assertNotNull(kodIm)
        assertEquals("https://cloud.example.com/?plugin/kodIM/", kodIm!!.launchUrl)

        assertNull(apps.find { it.id == "adminer" })
        assertNull(apps.find { it.id == "epubReader" })
    }

    @Test
    fun directoryCacheSupportsLruEvictionAndInvalidation() {
        val cache = KodDirectoryCache(maxEntries = 2, ttlMs = 60_000L)
        val res1 = PathListResult(
            currentPath = "{source:10}/",
            currentTitle = "A",
            items = listOf(KodFileItem(path = "{source:1}/", name = "a.txt", isFolder = false))
        )
        val res2 = PathListResult(
            currentPath = "{source:20}/",
            currentTitle = "B",
            items = listOf(KodFileItem(path = "{source:2}/", name = "b.txt", isFolder = false))
        )
        val res3 = PathListResult(
            currentPath = "{source:30}/",
            currentTitle = "C",
            items = listOf(KodFileItem(path = "{source:3}/", name = "c.txt", isFolder = false))
        )

        cache.put("acc1", "{source:10}/", SortField.NAME, SortOrder.ASC, res1)
        cache.put("acc1", "{source:20}/", SortField.NAME, SortOrder.ASC, res2)
        // Touch path 10 so path 20 becomes LRU
        assertNotNull(cache.get("acc1", "{source:10}/", SortField.NAME, SortOrder.ASC))
        // Insert path 30 -> evicts LRU path 20
        cache.put("acc1", "{source:30}/", SortField.NAME, SortOrder.ASC, res3)

        assertNotNull(cache.get("acc1", "{source:10}/", SortField.NAME, SortOrder.ASC))
        assertNull(cache.get("acc1", "{source:20}/", SortField.NAME, SortOrder.ASC))
        assertNotNull(cache.get("acc1", "{source:30}/", SortField.NAME, SortOrder.ASC))

        cache.invalidatePath("acc1", "{source:10}/")
        assertNull(cache.get("acc1", "{source:10}/", SortField.NAME, SortOrder.ASC))
        assertNotNull(cache.get("acc1", "{source:30}/", SortField.NAME, SortOrder.ASC))

        cache.invalidateAccount("acc1")
        assertNull(cache.get("acc1", "{source:30}/", SortField.NAME, SortOrder.ASC))
    }

    @Test
    fun h5SpaGuardInterceptsKodBoxRootAndPermitsPlugins() {
        val server = "https://cloud.example.com"
        assertTrue(isKodBoxMainSpaUrl("https://cloud.example.com/", server))
        assertTrue(isKodBoxMainSpaUrl("https://cloud.example.com/#explorer", server))
        assertTrue(isKodBoxMainSpaUrl("https://cloud.example.com/#desktop", server))
        assertTrue(isKodBoxMainSpaUrl("https://cloud.example.com/index.php?user/login", server))
        assertTrue(isKodBoxMainSpaUrl("https://cloud.example.com/index.php", server))

        assertFalse(isKodBoxMainSpaUrl("https://cloud.example.com/?plugin/onlyoffice/&path=%7Bsource:1%7D", server))
        assertFalse(isKodBoxMainSpaUrl("https://cloud.example.com/index.php?plugin/autoViewer/&path=%7Bsource:2%7D", server))
        assertFalse(isKodBoxMainSpaUrl("https://cloud.example.com/?plugin/drawio/&path=%7Bsource:3%7D", server))
        assertFalse(isKodBoxMainSpaUrl("https://onlyoffice-docs.example.org/web-apps/apps/documenteditor/mobile/index.html", server))
    }

    @Test
    fun pluginAndMediaUrlsIncludeAuthParameters() {
        val api = KodBoxApiClient()
        val docx = KodFileItem(path = "{source:19950}/", name = "report.docx", isFolder = false, ext = "docx")
        val mp4 = KodFileItem(path = "{source:13033}/", name = "clip.mp4", isFolder = false, ext = "mp4")

        val ooUrl = api.buildPluginPreviewUrl(sampleAccount, docx, OfficePluginViewer.ONLYOFFICE)
        assertTrue(ooUrl.startsWith("https://cloud.example.com/?plugin/onlyoffice/"))
        assertTrue(ooUrl.contains("accessToken=tok_123"))
        assertTrue(ooUrl.contains("ext=docx"))

        val mediaUrl = api.buildMediaStreamUrl(sampleAccount, mp4)
        assertTrue(mediaUrl.startsWith("https://cloud.example.com/?explorer/index/fileOut"))
        assertTrue(mediaUrl.contains("accessToken=tok_123"))
        assertTrue(mediaUrl.contains("HTTP_X_PLATFORM_1="))
    }

    @Test
    fun androidUserAgentIdentifiesAndroidClientInsteadOfOkHttp() {
        val ua = KodBoxApiClient.buildAndroidUserAgent()
        assertTrue(ua.contains("Android"))
        assertTrue(ua.contains("Mobile"))
        assertTrue(ua.contains("KodNext/"))
        assertFalse(ua.lowercase().contains("okhttp"))
    }
}
