package com.example.data

import com.example.model.KodPluginApp
import com.example.model.KodWorkspaceApp

/**
 * Dynamically discovers installed viewer/editor plugins from KodBox `user/view/plugins`
 * and merges them with a comprehensive fallback registry covering Office, CAD/3D (DWG),
 * eBooks (EPUB), Draw.io, MindMap (SMM/XMind), Excalidraw, PDF/OFD, PSD, and Email formats.
 */
object KodPluginRegistry {

    private val excludedPluginNames = setOf(
        "picasa", "photoswipe", "dplayer", "jplayer", "filethumb",
        "oemextshare", "htmleditor", "aceeditor", "msgwarning"
    )

    val builtInFallbackPlugins: List<KodPluginApp> = listOf(
        KodPluginApp(
            name = "onlyoffice",
            title = "OnlyOffice 在线",
            route = "?plugin/onlyoffice/",
            extensions = setOf(
                "docx", "doc", "odt", "rtf", "mht", "djvu", "fb2", "xps", "docm", "dotm", "dot", "dotx",
                "wps", "wpt", "xls", "xlsb", "xlsx", "ods", "csv", "xlt", "xltx", "xlsm", "et", "ett",
                "pps", "ppsx", "ppt", "pptx", "odp", "pot", "potx", "pptm", "ppsm", "potm", "dps", "dpt"
            ),
            sort = 500
        ),
        KodPluginApp(
            name = "yzOffice",
            title = "永中 Office",
            route = "?plugin/yzOffice/",
            extensions = setOf(
                "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "wps", "wpt",
                "xls", "xlsx", "xlt", "xlsm", "csv", "ppt", "pptx", "pps", "ppsx"
            ),
            sort = 300
        ),
        KodPluginApp(
            name = "officeLive",
            title = "OfficeLive",
            route = "?plugin/officeLive/",
            extensions = setOf(
                "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "xls", "xlsx", "xlt", "xlsb", "xlsm",
                "csv", "ppt", "pptx", "pps", "ppsx", "pptm", "potm", "ppam", "potx", "ppsm",
                "odt", "ods", "odp", "ott", "ots", "otp", "wps", "wpt"
            ),
            sort = 50
        ),
        KodPluginApp(
            name = "webodf",
            title = "OpenDocument 阅读",
            route = "?plugin/webodf/",
            extensions = setOf("odf", "odt", "ods", "odp"),
            sort = 40
        ),
        KodPluginApp(
            name = "pdfjs",
            title = "PDF 阅读器",
            route = "?plugin/pdfjs/",
            extensions = setOf("pdf", "djvu", "ofd", "ai"),
            sort = 1000
        ),
        KodPluginApp(
            name = "autoViewer",
            title = "3D / CAD 看图",
            route = "index.php?plugin/autoViewer/",
            extensions = setOf(
                "dwg", "dxf", "dxfb", "3dm", "3ds", "3mf", "amf", "bvh", "dae", "drc", "fbx",
                "glb", "gltf", "obj", "ply", "stl", "stp", "step", "igs", "iges", "ifc",
                "usdz", "usd", "vrml", "wrl", "blend", "kmz", "x_t", "x_b", "plt", "bim", "fcstd"
            ),
            sort = 400
        ),
        KodPluginApp(
            name = "epubReader",
            title = "电子书阅读器",
            route = "?plugin/epubReader/",
            extensions = setOf("epub", "mobi", "azw", "azw3", "cbz", "fb2", "fbz", "kf8"),
            sort = 200
        ),
        KodPluginApp(
            name = "drawio",
            title = "Drawio 流程图",
            route = "?plugin/drawio/",
            extensions = setOf("drawio", "vsd", "vsdx", "vdx", "graphml", "dio"),
            sort = 200
        ),
        KodPluginApp(
            name = "minder",
            title = "思绪思维导图",
            route = "?plugin/minder/",
            extensions = setOf("smm", "mind", "xmind", "km"),
            sort = 200
        ),
        KodPluginApp(
            name = "excalidraw",
            title = "Excalidraw 白板",
            route = "?plugin/excalidraw/",
            extensions = setOf("edraw", "excalidraw", "excalidrawlib"),
            sort = 200
        ),
        KodPluginApp(
            name = "oemLe5leDraw",
            title = "乐吾乐绘图",
            route = "?plugin/oemLe5leDraw/",
            extensions = setOf("vsdu", "lt"),
            sort = 100
        ),
        KodPluginApp(
            name = "emlViewer",
            title = "EML 邮件阅读",
            route = "?plugin/emlViewer/",
            extensions = setOf("eml", "mht", "msg", "mhtml", "emlx"),
            sort = 150
        ),
        KodPluginApp(
            name = "photopea",
            title = "在线 PS (Photopea)",
            route = "?plugin/photopea/",
            extensions = setOf(
                "psd", "psb", "ai", "xd", "sketch", "xcf", "eps", "tif", "tiff",
                "raw", "dng", "cr2", "nef", "arw", "emf", "tga", "dds"
            ),
            sort = 10
        ),
        KodPluginApp(
            name = "svgEditor",
            title = "SVG 编辑器",
            route = "?plugin/svgEditor/",
            extensions = setOf("svg"),
            sort = 80
        )
    )

    /**
     * Parses KodBox's `index.php?user/view/plugins` JavaScript bundle to discover all
     * viewer/editor plugins installed on the user's server along with their supported extensions.
     */
    fun parsePluginsFromJs(jsBundle: String): List<KodPluginApp> {
        if (jsBundle.isBlank()) return emptyList()
        val blockMarker = Regex("""/\*\s*\[([^\]/]+)/[^\]]*\]\s*\*/""")
        val matches = blockMarker.findAll(jsBundle).toList()
        val discovered = linkedMapOf<String, KodPluginApp>()

        val titleRegex = Regex("""title\s*:\s*['"]([^'"]+)['"]""")
        val literalExtRegex = Regex("""ext\s*:\s*['"]([^'"]+)['"]""")
        val varExtRegex = Regex("""ext\s*:\s*([a-zA-Z_][a-zA-Z0-9_]*)""")
        val sortRegex = Regex("""sort\s*:\s*['"]?(\d+)['"]?""")
        val openFileRegex = Regex("""core\.openFile\(\s*['"]([^'"]+)['"]""")

        for (i in matches.indices) {
            val pluginName = matches[i].groupValues[1].trim()
            if (pluginName.isBlank() || pluginName.lowercase() in excludedPluginNames) continue

            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else jsBundle.length
            val block = jsBundle.substring(start, end)
            if (!block.contains("appList.push") || !block.contains("core.openFile")) continue

            val openFileRaw = openFileRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (openFileRaw.isBlank()) continue

            val route = normalizePluginRoute(openFileRaw, pluginName) ?: continue
            val title = titleRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { pluginName }
            val sort = sortRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 100

            // Extract extension string either from literal ext:"..." or var <name> = "..."
            var extRaw = literalExtRegex.find(block)?.groupValues?.get(1).orEmpty()
            if (extRaw.isBlank()) {
                val varName = varExtRegex.find(block)?.groupValues?.get(1).orEmpty()
                if (varName.isNotBlank()) {
                    val varDefRegex = Regex("""var\s+${Regex.escape(varName)}\s*=\s*['"]([^'"]+)['"]""")
                    extRaw = varDefRegex.find(block)?.groupValues?.get(1).orEmpty()
                }
            }

            val extensions = extRaw.split(',', '|', ';', ' ')
                .map { it.trim().lowercase().trimStart('.') }
                .filter { it.isNotBlank() && it.length <= 16 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
                .toSet()

            if (extensions.isEmpty()) continue

            discovered[pluginName.lowercase()] = KodPluginApp(
                name = pluginName,
                title = title,
                route = route,
                extensions = extensions,
                sort = sort,
                isFromServer = true
            )
        }

        return discovered.values.sortedByDescending { it.sort }
    }

    /**
     * Resolves all compatible plugins for a given file extension, prioritizing server-discovered
     * plugins first and keeping built-in defaults only when the server list hasn't loaded yet.
     */
    fun matchPluginsForExtension(
        ext: String,
        serverPlugins: List<KodPluginApp>
    ): List<KodPluginApp> {
        val cleanExt = ext.trim().lowercase().trimStart('.')
        if (cleanExt.isBlank()) return emptyList()

        val activeCatalog = if (serverPlugins.isNotEmpty()) {
            // Merge server plugins with any built-in plugin of a different name?
            // Prefer exact server plugins so we don't offer uninstalled plugins that might redirect to H5!
            val serverMatched = serverPlugins.filter { cleanExt in it.extensions }
            if (serverMatched.isNotEmpty()) {
                serverMatched
            } else {
                builtInFallbackPlugins.filter { cleanExt in it.extensions }
            }
        } else {
            builtInFallbackPlugins.filter { cleanExt in it.extensions }
        }

        return activeCatalog.sortedByDescending { it.sort }
    }

    /**
     * Discovers interactive KodBox workspace & menu plugins (`project` 项目管理, `kodIM` 即时通讯,
     * `aiDoc` AI助手, `HelpCenter` 帮助中心, `drawio`, `minder`, `excalidraw`, `photopea`, `webConsole`, etc.)
     * from KodBox's `user/view/plugins` bundle.
     */
    fun parseWorkspaceAppsFromJs(jsBundle: String, serverUrl: String): List<KodWorkspaceApp> {
        if (jsBundle.isBlank()) return emptyList()
        val base = serverUrl.trimEnd('/')
        val blockMarker = Regex("""/\*\s*\[([^\]/]+)/[^\]]*\]\s*\*/""")
        val matches = blockMarker.findAll(jsBundle).toList()
        val discovered = linkedMapOf<String, KodWorkspaceApp>()

        val excludedWorkspaceIds = setOf(
            "debug", "filemissingexport", "msgwarning", "oemextshare", "picasa",
            "photoswipe", "dplayer", "jplayer", "filethumb", "aceeditor", "htmleditor"
        )

        val friendlyMeta = mapOf(
            "project" to Triple("项目", "项目管理与任务看板", 1000),
            "kodim" to Triple("即时通讯", "团队单聊、群聊与文件传输", 900),
            "aidoc" to Triple("AI 助手", "网盘智能问答与文档助手", 850),
            "drawio" to Triple("Drawio 绘图", "流程图与架构图绘制", 700),
            "minder" to Triple("思绪导图", "思维导图在线绘制", 680),
            "excalidraw" to Triple("白板绘图", "Excalidraw 手绘协作白板", 660),
            "photopea" to Triple("在线 PS", "Photopea 专业图像设计", 600),
            "svgeditor" to Triple("SVG 编辑器", "矢量图形在线编辑", 580),
            "helpcenter" to Triple("帮助中心", "使用手册与知识文档", 500),
            "webconsole" to Triple("Web 终端", "服务器在线命令行终端", 420),
            "adminer" to Triple("数据库管理", "Adminer 数据库管理工具", 400),
            "mcp" to Triple("MCP 配置", "模型上下文协议管理", 380)
        )
        val adminOnlyIds = setOf("webconsole", "adminer", "mcp")

        val titleRegex = Regex("""(?:menuName|appName|title|name)\s*[:=]\s*['"]([^'"]{1,32})['"]""")
        val descRegex = Regex("""(?:appDesc|docsDesc|desc)\s*[:=]\s*['"]([^'"]{2,120})['"]""")
        val iconRegex = Regex("""(?:iconFile|icon)\s*[:=]\s*['"](https?://[^'"]+\.(?:png|svg|jpg|webp))['"]""")
        val appUrlRegex = Regex("""(?:appUrl|pluginApi|url)\s*[:=]\s*['"](https?://[^'"]+)['"]""")
        val routerPageRegex = Regex("""Router\.mapPage\s*\(\s*\{[^}]*page\s*:\s*['"]([^'"]+)['"]""")
        val routerIframeRegex = Regex("""Router\.mapIframe\s*\(\s*\{[^}]*url\s*:\s*['"](https?://[^'"]+)['"]""")

        for (i in matches.indices) {
            val pluginName = matches[i].groupValues[1].trim()
            val lower = pluginName.lowercase()
            if (pluginName.isBlank() || lower in excludedWorkspaceIds) continue

            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else jsBundle.length
            val block = jsBundle.substring(start, end)

            val hasMenu = block.contains("main.menu.loadBefore")
            val hasRouterPage = block.contains("Router.mapPage")
            val hasRouterIframe = block.contains("Router.mapIframe")
            val hasLightApp = block.contains("explorer.lightApp.load")
            if (!hasMenu && !hasRouterPage && !hasRouterIframe && !hasLightApp) continue

            val routerPageId = routerPageRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val iframeUrl = routerIframeRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val directAppUrl = appUrlRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()

            val (launchUrl, isRouterPage) = when {
                iframeUrl.isNotBlank() -> iframeUrl to false
                directAppUrl.isNotBlank() && !directAppUrl.contains("static/images/") -> directAppUrl to false
                routerPageId.isNotBlank() -> "$base/#$routerPageId" to true
                hasRouterPage || lower == "project" || lower == "aidoc" -> "$base/#$pluginName" to true
                else -> "$base/?plugin/$pluginName/" to false
            }

            val meta = friendlyMeta[lower]
            val parsedTitle = titleRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val title = meta?.first ?: parsedTitle.ifBlank { pluginName }
            val desc = meta?.second ?: descRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val iconUrl = iconRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val priority = meta?.third ?: if (hasMenu) 550 else 300

            discovered[lower] = KodWorkspaceApp(
                id = pluginName,
                title = title,
                description = desc,
                launchUrl = launchUrl,
                iconUrl = iconUrl,
                isRouterPage = isRouterPage,
                isAdminOnly = lower in adminOnlyIds,
                priority = priority
            )
        }

        return discovered.values.sortedByDescending { it.priority }
    }

    private fun normalizePluginRoute(rawOpenUrl: String, pluginName: String): String? {
        val qIdx = rawOpenUrl.indexOf("?plugin/")
        if (qIdx >= 0) {
            val prefix = if (rawOpenUrl.substring(0, qIdx).endsWith("index.php", ignoreCase = true)) {
                "index.php"
            } else {
                ""
            }
            val tail = rawOpenUrl.substring(qIdx)
            return "$prefix$tail"
        }
        return "?plugin/$pluginName/"
    }
}
