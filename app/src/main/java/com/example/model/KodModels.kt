package com.example.model

enum class ThemePreference(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

enum class MainTab(val label: String) {
    FILES("网盘"),
    MEDIA("相册"),
    SHARED("协作"),
    SYNC("同步"),
    ME("我的")
}

enum class SyncSubTab(val label: String) {
    RULES("双向同步"),
    TRANSFERS("传输队列")
}

enum class ShareAccessMode(val label: String, val subtitle: String, val notDownload: Boolean, val notView: Boolean, val canUpload: Boolean) {
    READ_ONLY_DOWNLOAD("只读（可预览和下载）", "访客可在线查看并下载文件，不可上传或修改", false, false, false),
    READ_ONLY_PREVIEW("仅预览（禁止下载）", "访客仅可在线预览，禁止下载或修改", true, false, false),
    READ_WRITE("读写协作（可上传与编辑）", "访客可查看、下载，并允许向该目录上传或编辑文件", false, false, true)
}

enum class SyncFrequency(val label: String, val intervalMinutes: Long) {
    REALTIME("实时同步", 15L),
    HOURLY("每小时", 60L),
    DAILY("每天", 1440L),
    MANUAL("仅手动", 0L)
}

enum class SyncDirection(val label: String) {
    TWO_WAY("双向同步"),
    UPLOAD_ONLY("仅上传到网盘"),
    DOWNLOAD_ONLY("仅下载到本地")
}

enum class SyncRunStatus(val label: String) {
    IDLE("等待同步"),
    SYNCING("正在同步…"),
    SUCCESS("已同步"),
    ERROR("同步失败")
}

data class SyncRule(
    val id: String,
    val accountId: String,
    val name: String,
    val localTreeUri: String,
    val localFolderLabel: String,
    val remotePath: String,
    val remoteFolderLabel: String,
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val frequency: SyncFrequency = SyncFrequency.HOURLY,
    val wifiOnly: Boolean = true,
    val enabled: Boolean = true,
    val lastSyncTime: Long = 0L,
    val lastStatus: SyncRunStatus = SyncRunStatus.IDLE,
    val lastSummary: String = "尚未执行过同步"
)

enum class FilterFileType(val label: String) {
    ALL("全部类型"),
    FOLDER("文件夹"),
    DOCUMENT("文档"),
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    ARCHIVE("压缩包"),
    CODE("代码/文本")
}

enum class FilterDateRange(val label: String, val withinDays: Int?) {
    ANY("不限日期", null),
    TODAY("今天", 1),
    LAST_7_DAYS("最近 7 天", 7),
    LAST_30_DAYS("最近 30 天", 30),
    LAST_YEAR("最近一年", 365)
}

enum class FilterSizeRange(val label: String, val minBytes: Long?, val maxBytes: Long?) {
    ANY("不限大小", null, null),
    UNDER_1MB("< 1 MB", null, 1024L * 1024L),
    MB_1_TO_10("1 - 10 MB", 1024L * 1024L, 10L * 1024L * 1024L),
    MB_10_TO_100("10 - 100 MB", 10L * 1024L * 1024L, 100L * 1024L * 1024L),
    OVER_100MB("> 100 MB", 100L * 1024L * 1024L, null)
}

data class SearchFilterState(
    val fileType: FilterFileType = FilterFileType.ALL,
    val dateRange: FilterDateRange = FilterDateRange.ANY,
    val sizeRange: FilterSizeRange = FilterSizeRange.ANY
) {
    val activeCount: Int
        get() = listOf(
            fileType != FilterFileType.ALL,
            dateRange != FilterDateRange.ANY,
            sizeRange != FilterSizeRange.ANY
        ).count { it }

    val hasActiveFilters: Boolean
        get() = activeCount > 0
}

enum class ViewMode {
    LIST,
    GRID
}

enum class SortField(val apiKey: String, val label: String) {
    NAME("name", "按名称"),
    MODIFY_TIME("modifyTime", "按修改时间"),
    SIZE("size", "按文件大小"),
    EXT("ext", "按文件类型")
}

enum class SortOrder(val apiKey: String, val label: String) {
    ASC("up", "升序"),
    DESC("down", "降序")
}

enum class ShareSubTab(val label: String) {
    EXTERNAL_LINKS("外链共享"),
    MY_COLLAB("我的协作"),
    SHARED_WITH_ME("与我协作")
}

data class KodPluginApp(
    val name: String,
    val title: String,
    val route: String,
    val extensions: Set<String>,
    val sort: Int = 100,
    val isFromServer: Boolean = false
)

/**
 * Represents an interactive KodBox Workspace / Menu / LightApp plugin
 * (e.g. `project` 项目管理, `kodIM` 即时通讯, `aiDoc` AI助手, `HelpCenter` 帮助中心, `drawio`, `minder`).
 */
data class KodWorkspaceApp(
    val id: String,
    val title: String,
    val description: String = "",
    val launchUrl: String,
    val iconUrl: String = "",
    val isRouterPage: Boolean = false,
    val isAdminOnly: Boolean = false,
    val priority: Int = 100
)

data class KodUserLogItem(
    val id: String,
    val type: String,
    val title: String,
    val detail: String = "",
    val createTime: Long = 0L
)

data class KodUserDeviceItem(
    val ip: String,
    val address: String,
    val userAgent: String,
    val action: String,
    val createTime: Long = 0L
)

data class KodAdminMember(
    val userId: String,
    val name: String,
    val nickName: String,
    val roleId: String,
    val email: String,
    val phone: String,
    val sizeUse: Long,
    val sizeMax: Long,
    val status: Int,
    val lastLogin: Long
) {
    val displayName: String get() = nickName.ifBlank { name }
}

data class KodAdminGroup(
    val groupId: String,
    val name: String,
    val parentId: String,
    val sizeUse: Long,
    val sizeMax: Long
)

data class KodAdminStorage(
    val id: String,
    val name: String,
    val driver: String,
    val isDefault: Boolean,
    val sizeUse: Long,
    val sizeMax: Long
)

data class KodAdminPlugin(
    val id: String,
    val name: String,
    val title: String,
    val version: String,
    val category: String,
    val enabled: Boolean
)

data class KodAdminOverview(
    val systemName: String = "KodBox",
    val systemDesc: String = "",
    val totalMembers: Int = 0,
    val members: List<KodAdminMember> = emptyList(),
    val groups: List<KodAdminGroup> = emptyList(),
    val storages: List<KodAdminStorage> = emptyList(),
    val plugins: List<KodAdminPlugin> = emptyList()
)

enum class KodSpace(val label: String, val virtualPath: String, val isPrimarySpace: Boolean = false) {
    PERSONAL("个人空间", "", isPrimarySpace = true),
    ENTERPRISE("企业网盘", "{block:enterprise}/", isPrimarySpace = true),
    FAVORITES("收藏夹", "{userFav}/", isPrimarySpace = true),
    SHARED_WITH_ME("与我协作", "{shareToMe}/", isPrimarySpace = true),
    RECENT("最近文档", "{userRencent}/", isPrimarySpace = false),
    FILE_TYPES("文档分类", "{block:fileType}/", isPrimarySpace = false),
    TAGS("文件标签", "{block:fileTag}/", isPrimarySpace = false),
    RECYCLE_BIN("回收站", "{userRecycle}/", isPrimarySpace = false)
}

enum class KodFileTypeCategory(val label: String, val pathSegment: String) {
    ALL("全部", ""),
    DOC("文档", "doc"),
    IMAGE("图片", "image"),
    VIDEO("视频", "movie"),
    MUSIC("音频", "music"),
    ARCHIVE("压缩包", "zip"),
    OTHER("其他", "others");

    val virtualPath: String
        get() = if (pathSegment.isBlank()) "{block:fileType}/" else "{userFileType:$pathSegment}/"
}

enum class OfficePluginViewer(val route: String, val label: String) {
    ONLYOFFICE("?plugin/onlyoffice/", "OnlyOffice 插件"),
    OFFICE_LIVE("?plugin/officeLive/", "OfficeLive 备用"),
    PDF_JS("?plugin/pdfjs/", "PDF 插件阅读")
}

data class KodAccount(
    val id: String,
    val serverUrl: String,
    val username: String,
    val nickName: String,
    val avatarUrl: String = "",
    val email: String = "",
    val phone: String = "",
    val sex: String = "1",
    val roleId: String = "",
    val isAdmin: Boolean = false,
    val departments: List<String> = emptyList(),
    val accessToken: String = "",
    val cookiesHeader: String = "",
    val csrfToken: String = "",
    val userId: String = "",
    val sizeUse: Long = 0L,
    val sizeMax: Long = 0L,
    val serverVersion: String = "",
    val siteName: String = "KodBox",
    val personalRootPath: String = "{source:1}/",
    val groupRootPath: String = "{block:enterprise}/",
    val lastLoginAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = nickName.ifBlank { username }

    val roleLabel: String
        get() = if (isAdmin) "系统管理员" else "普通成员"

    val isHttps: Boolean
        get() = serverUrl.startsWith("https://", ignoreCase = true)

    val hostLabel: String
        get() = serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    val quotaProgress: Float
        get() = if (sizeMax > 0L) (sizeUse.toDouble() / sizeMax.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

data class KodTag(
    val id: String,
    val name: String,
    val style: String = "label-blue-normal",
    val colorHex: Long = 0xFF0074C8L
)

data class KodFileItem(
    val path: String,
    val sourceId: String = "",
    val name: String,
    val isFolder: Boolean,
    val size: Long = 0L,
    val modifyTime: Long = 0L,
    val createTime: Long = 0L,
    val ext: String = "",
    val isFav: Boolean = false,
    val isShare: Boolean = false,
    val tags: List<KodTag> = emptyList(),
    val fileThumb: String = "",
    val hashMd5: String = "",
    val ownerName: String = "",
    val description: String = "",
    val isReadable: Boolean = true,
    val isWriteable: Boolean = true,
    val isRecycleItem: Boolean = false,
    val childCount: Int = 0
) {
    val normalizedExt: String
        get() = ext.ifBlank {
            if (!isFolder && name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        }.lowercase()

    val isImage: Boolean
        get() = !isFolder && normalizedExt in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")

    val isVideo: Boolean
        get() = !isFolder && normalizedExt in setOf("mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "ts", "ogv", "3gp")

    val isAudio: Boolean
        get() = !isFolder && normalizedExt in setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus")

    val isOfficeDocument: Boolean
        get() = !isFolder && normalizedExt in setOf(
            "doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "rtf", "wps", "wpt",
            "xls", "xlsx", "xlsb", "xlsm", "xlt", "xltx", "ods", "et", "ett",
            "ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm", "odp", "dps", "dpt", "xps"
        )

    val isPdf: Boolean
        get() = !isFolder && normalizedExt in setOf("pdf", "ofd", "djvu")

    val isCadOr3d: Boolean
        get() = !isFolder && normalizedExt in setOf(
            "dwg", "dxf", "dxfb", "stl", "obj", "fbx", "glb", "gltf", "stp", "step", "igs", "iges", "3ds", "ply", "ifc", "blend", "usdz"
        )

    val isEbook: Boolean
        get() = !isFolder && normalizedExt in setOf("epub", "mobi", "azw", "azw3", "cbz", "fb2", "fbz", "kf8")

    val isDiagramOrMindMap: Boolean
        get() = !isFolder && normalizedExt in setOf(
            "drawio", "vsd", "vsdx", "vdx", "graphml", "dio", "smm", "mind", "xmind", "km", "edraw", "excalidraw", "vsdu", "lt"
        )

    val isArchive: Boolean
        get() = !isFolder && normalizedExt in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    val isEditableText: Boolean
        get() = !isFolder && normalizedExt in setOf(
            "txt", "md", "markdown", "json", "xml", "yaml", "yml", "ini", "conf", "cfg",
            "log", "csv", "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh",
            "php", "sql", "c", "cpp", "h", "go", "rs", "toml", "properties"
        )
}

data class KodShareItem(
    val shareId: String,
    val shareHash: String,
    val title: String,
    val path: String,
    val sourceId: String,
    val shareUrl: String,
    val isLink: Boolean = true,
    val isShareTo: Boolean = false,
    val password: String = "",
    val timeTo: Long = 0L,
    val numView: Int = 0,
    val numDownload: Int = 0,
    val notDownload: Boolean = false,
    val notView: Boolean = false,
    val canUpload: Boolean = false,
    val isFolder: Boolean = false,
    val size: Long = 0L,
    val ext: String = "",
    val createTime: Long = 0L
)

data class BreadcrumbNode(
    val label: String,
    val path: String
)

enum class TransferType {
    UPLOAD,
    DOWNLOAD
}

enum class TransferStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferTask(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val localPath: String = "",
    val type: TransferType,
    val progress: Float = 0f,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.RUNNING,
    val errorMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ServerProbeInfo(
    val normalizedUrl: String,
    val reachable: Boolean,
    val siteName: String = "KodBox",
    val version: String = "",
    val needCheckCode: Boolean = false,
    val csrfToken: String = "",
    val errorMessage: String = ""
)

data class AppPreferences(
    val viewMode: ViewMode = ViewMode.LIST,
    val sortField: SortField = SortField.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val showHiddenFiles: Boolean = false,
    val showThumbnails: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val pinnedAppId: String = "project"
)

data class PathListResult(
    val currentPath: String,
    val currentTitle: String,
    val items: List<KodFileItem>,
    val tags: List<KodTag> = emptyList(),
    val breadcrumbs: List<BreadcrumbNode> = emptyList()
)
