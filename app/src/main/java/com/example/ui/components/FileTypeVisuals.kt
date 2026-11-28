package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CorporateFare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.R
import com.example.data.KodFormatters
import com.example.model.KodFileItem
import com.example.ui.theme.FileArchiveColor
import com.example.ui.theme.FileAudioColor
import com.example.ui.theme.FileCodeColor
import com.example.ui.theme.FileDefaultColor
import com.example.ui.theme.FileDocColor
import com.example.ui.theme.FileDepartmentFolderColor
import com.example.ui.theme.FileFolderColor
import com.example.ui.theme.FileImageColor
import com.example.ui.theme.FilePdfColor
import com.example.ui.theme.FileSheetColor
import com.example.ui.theme.FileSlideColor
import com.example.ui.theme.FileVideoColor

data class FileVisualSpec(
    val icon: ImageVector,
    val tint: Color
)

enum class DocumentGlyphStyle {
    WORD_LINES,
    SPREADSHEET_GRID,
    SLIDE_DECK,
    PDF_DOCUMENT,
    PLAIN_DOCUMENT
}

data class DocumentBadgeSpec(
    val emblem: String,
    val formatTag: String,
    val topColor: Color,
    val bottomColor: Color,
    val style: DocumentGlyphStyle
)

/**
 * Resolves authentic, format-specific Office and document thumbnail badge specs
 * covering Microsoft Office (Word/Excel/PowerPoint/Visio/OneNote/Access/Publisher),
 * WPS Office (WPS/ET/DPS), OpenDocument (ODT/ODS/ODP), Apple iWork (Pages/Numbers/Keynote),
 * PDF/OFD/DJVU, CSV/RTF, and Markdown/Text.
 */
fun resolveDocumentBadge(ext: String): DocumentBadgeSpec? {
    return when (ext.trim().lowercase()) {
        // Microsoft Word
        "doc", "docx", "docm", "dot", "dotx", "dotm" -> DocumentBadgeSpec(
            emblem = "W",
            formatTag = ext.uppercase().take(4),
            topColor = Color(0xFF2B6CB0),
            bottomColor = Color(0xFF185ABD),
            style = DocumentGlyphStyle.WORD_LINES
        )
        // WPS Writer
        "wps", "wpt" -> DocumentBadgeSpec(
            emblem = "W",
            formatTag = ext.uppercase(),
            topColor = Color(0xFF3B82F6),
            bottomColor = Color(0xFF1D4ED8),
            style = DocumentGlyphStyle.WORD_LINES
        )
        // OpenDocument Text / Rich Text / Apple Pages
        "odt" -> DocumentBadgeSpec(
            emblem = "W",
            formatTag = "ODT",
            topColor = Color(0xFF0284C7),
            bottomColor = Color(0xFF0369A1),
            style = DocumentGlyphStyle.WORD_LINES
        )
        "rtf" -> DocumentBadgeSpec(
            emblem = "T",
            formatTag = "RTF",
            topColor = Color(0xFF4F46E5),
            bottomColor = Color(0xFF3730A3),
            style = DocumentGlyphStyle.WORD_LINES
        )
        "pages" -> DocumentBadgeSpec(
            emblem = "P",
            formatTag = "PAGE",
            topColor = Color(0xFFF97316),
            bottomColor = Color(0xFFEA580C),
            style = DocumentGlyphStyle.WORD_LINES
        )
        // Microsoft Excel
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx" -> DocumentBadgeSpec(
            emblem = "X",
            formatTag = ext.uppercase().take(4),
            topColor = Color(0xFF1E8E5A),
            bottomColor = Color(0xFF107C41),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        // WPS Spreadsheets
        "et", "ett" -> DocumentBadgeSpec(
            emblem = "S",
            formatTag = ext.uppercase(),
            topColor = Color(0xFF22C55E),
            bottomColor = Color(0xFF15803D),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        // CSV / TSV / OpenDocument Spreadsheet / Apple Numbers
        "csv", "tsv" -> DocumentBadgeSpec(
            emblem = "X",
            formatTag = ext.uppercase(),
            topColor = Color(0xFF10B981),
            bottomColor = Color(0xFF047857),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        "ods" -> DocumentBadgeSpec(
            emblem = "X",
            formatTag = "ODS",
            topColor = Color(0xFF16A34A),
            bottomColor = Color(0xFF166534),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        "numbers" -> DocumentBadgeSpec(
            emblem = "N",
            formatTag = "NUM",
            topColor = Color(0xFF22C55E),
            bottomColor = Color(0xFF15803D),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        // Microsoft PowerPoint
        "ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm" -> DocumentBadgeSpec(
            emblem = "P",
            formatTag = ext.uppercase().take(4),
            topColor = Color(0xFFE05A34),
            bottomColor = Color(0xFFC43E1C),
            style = DocumentGlyphStyle.SLIDE_DECK
        )
        // WPS Presentation
        "dps", "dpt" -> DocumentBadgeSpec(
            emblem = "P",
            formatTag = ext.uppercase(),
            topColor = Color(0xFFFB923C),
            bottomColor = Color(0xFFEA580C),
            style = DocumentGlyphStyle.SLIDE_DECK
        )
        // OpenDocument Presentation / Apple Keynote
        "odp" -> DocumentBadgeSpec(
            emblem = "P",
            formatTag = "ODP",
            topColor = Color(0xFFF97316),
            bottomColor = Color(0xFFC2410C),
            style = DocumentGlyphStyle.SLIDE_DECK
        )
        "key" -> DocumentBadgeSpec(
            emblem = "K",
            formatTag = "KEY",
            topColor = Color(0xFF0EA5E9),
            bottomColor = Color(0xFF0369A1),
            style = DocumentGlyphStyle.SLIDE_DECK
        )
        // PDF / OFD / DJVU / XPS
        "pdf" -> DocumentBadgeSpec(
            emblem = "PDF",
            formatTag = "PDF",
            topColor = Color(0xFFEF4444),
            bottomColor = Color(0xFFB91C1C),
            style = DocumentGlyphStyle.PDF_DOCUMENT
        )
        "ofd" -> DocumentBadgeSpec(
            emblem = "OFD",
            formatTag = "OFD",
            topColor = Color(0xFFE11D48),
            bottomColor = Color(0xFF9F1239),
            style = DocumentGlyphStyle.PDF_DOCUMENT
        )
        "djvu", "xps" -> DocumentBadgeSpec(
            emblem = ext.uppercase().take(3),
            formatTag = ext.uppercase().take(4),
            topColor = Color(0xFFDC2626),
            bottomColor = Color(0xFF991B1B),
            style = DocumentGlyphStyle.PDF_DOCUMENT
        )
        // Visio / OneNote / Access / Publisher
        "vsd", "vsdx" -> DocumentBadgeSpec(
            emblem = "V",
            formatTag = ext.uppercase().take(4),
            topColor = Color(0xFF4F6BED),
            bottomColor = Color(0xFF3955A3),
            style = DocumentGlyphStyle.PLAIN_DOCUMENT
        )
        "one", "onetoc2" -> DocumentBadgeSpec(
            emblem = "N",
            formatTag = "ONE",
            topColor = Color(0xFF9333EA),
            bottomColor = Color(0xFF7719AA),
            style = DocumentGlyphStyle.WORD_LINES
        )
        "accdb", "mdb" -> DocumentBadgeSpec(
            emblem = "A",
            formatTag = if (ext.equals("mdb", true)) "MDB" else "DB",
            topColor = Color(0xFFC2414B),
            bottomColor = Color(0xFFA4373A),
            style = DocumentGlyphStyle.SPREADSHEET_GRID
        )
        "pub" -> DocumentBadgeSpec(
            emblem = "P",
            formatTag = "PUB",
            topColor = Color(0xFF0D9488),
            bottomColor = Color(0xFF077568),
            style = DocumentGlyphStyle.PLAIN_DOCUMENT
        )
        // Markdown / Plain Text
        "md", "markdown" -> DocumentBadgeSpec(
            emblem = "M↓",
            formatTag = "MD",
            topColor = Color(0xFF4F46E5),
            bottomColor = Color(0xFF312E81),
            style = DocumentGlyphStyle.WORD_LINES
        )
        "txt", "log" -> DocumentBadgeSpec(
            emblem = "T",
            formatTag = ext.uppercase(),
            topColor = Color(0xFF64748B),
            bottomColor = Color(0xFF475569),
            style = DocumentGlyphStyle.WORD_LINES
        )
        else -> null
    }
}

@Composable
fun OfficeDocumentThumbnailIcon(
    spec: DocumentBadgeSpec,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val boxWidth = if (compact) 38.dp else 54.dp
    val boxHeight = if (compact) 42.dp else 62.dp
    Box(
        modifier = modifier.size(width = boxWidth, height = boxHeight),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fold = w * 0.28f
            val r = w * 0.14f

            // Folded-corner document silhouette
            val pagePath = Path().apply {
                moveTo(r, 0f)
                lineTo(w - fold, 0f)
                lineTo(w, fold)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(r, h)
                quadraticTo(0f, h, 0f, h - r)
                lineTo(0f, r)
                quadraticTo(0f, 0f, r, 0f)
                close()
            }
            drawPath(
                path = pagePath,
                brush = Brush.verticalGradient(listOf(spec.topColor, spec.bottomColor))
            )

            // Dog-ear corner flap
            val foldPath = Path().apply {
                moveTo(w - fold, 0f)
                lineTo(w - fold, fold - r * 0.25f)
                quadraticTo(w - fold, fold, w - fold + r * 0.25f, fold)
                lineTo(w, fold)
                close()
            }
            drawPath(path = foldPath, color = Color.White.copy(alpha = 0.28f))

            // Subtle format-specific background motif
            val motifAlpha = 0.16f
            when (spec.style) {
                DocumentGlyphStyle.WORD_LINES -> {
                    val lineH = h * 0.045f
                    drawRoundRect(
                        color = Color.White.copy(alpha = motifAlpha),
                        topLeft = Offset(w * 0.16f, h * 0.20f),
                        size = Size(w * 0.44f, lineH),
                        cornerRadius = CornerRadius(lineH, lineH)
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = motifAlpha),
                        topLeft = Offset(w * 0.16f, h * 0.30f),
                        size = Size(w * 0.66f, lineH),
                        cornerRadius = CornerRadius(lineH, lineH)
                    )
                }
                DocumentGlyphStyle.SPREADSHEET_GRID -> {
                    val cellW = w * 0.28f
                    val cellH = h * 0.10f
                    drawRoundRect(
                        color = Color.White.copy(alpha = motifAlpha),
                        topLeft = Offset(w * 0.16f, h * 0.18f),
                        size = Size(cellW, cellH),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = motifAlpha),
                        topLeft = Offset(w * 0.50f, h * 0.18f),
                        size = Size(cellW, cellH),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
                DocumentGlyphStyle.SLIDE_DECK -> {
                    drawCircle(
                        color = Color.White.copy(alpha = motifAlpha),
                        radius = w * 0.16f,
                        center = Offset(w * 0.72f, h * 0.34f)
                    )
                }
                DocumentGlyphStyle.PDF_DOCUMENT, DocumentGlyphStyle.PLAIN_DOCUMENT -> {
                    val barH = h * 0.04f
                    drawRoundRect(
                        color = Color.White.copy(alpha = motifAlpha),
                        topLeft = Offset(w * 0.16f, h * 0.22f),
                        size = Size(w * 0.48f, barH),
                        cornerRadius = CornerRadius(barH, barH)
                    )
                }
            }

            // Darker bottom format-tag ribbon
            val ribbonTop = h * 0.66f
            val ribbonPath = Path().apply {
                moveTo(0f, ribbonTop)
                lineTo(w, ribbonTop)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(r, h)
                quadraticTo(0f, h, 0f, h - r)
                close()
            }
            drawPath(path = ribbonPath, color = Color.Black.copy(alpha = 0.24f))
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(0.66f)
                    .fillMaxWidth()
                    .padding(top = if (compact) 4.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                val isShortEmblem = spec.emblem.length <= 2
                Text(
                    text = spec.emblem,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = when {
                        compact && isShortEmblem -> 15.sp
                        compact -> 10.sp
                        isShortEmblem -> 21.sp
                        else -> 13.sp
                    },
                    lineHeight = if (compact) 16.sp else 22.sp,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = spec.formatTag,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 7.5.sp else 9.5.sp,
                    lineHeight = if (compact) 8.5.sp else 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun DepartmentFolderThumbnailIcon(
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val boxSize = if (compact) 46.dp else 62.dp
    val folderIconSize = if (compact) 28.dp else 44.dp
    val badgeSize = if (compact) 18.dp else 24.dp
    val badgeIconSize = if (compact) 11.dp else 15.dp

    Box(
        modifier = modifier
            .size(boxSize)
            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        FileDepartmentFolderColor.copy(alpha = 0.18f),
                        Color(0xFF0EA5E9).copy(alpha = 0.14f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = FileDepartmentFolderColor.copy(alpha = 0.28f),
                shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = "部门文件夹",
            tint = FileDepartmentFolderColor,
            modifier = Modifier.size(folderIconSize)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(if (compact) 3.dp else 5.dp)
                .size(badgeSize)
                .clip(CircleShape)
                .background(FileDepartmentFolderColor)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CorporateFare,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(badgeIconSize)
            )
        }
    }
}

fun resolveFileVisual(item: KodFileItem): FileVisualSpec {
    if (item.isFolder) {
        return if (item.isDepartmentFolder) {
            FileVisualSpec(Icons.Filled.CorporateFare, FileDepartmentFolderColor)
        } else {
            FileVisualSpec(Icons.Filled.Folder, FileFolderColor)
        }
    }
    resolveDocumentBadge(item.normalizedExt)?.let { badge ->
        val icon = when (badge.style) {
            DocumentGlyphStyle.WORD_LINES -> Icons.Filled.Description
            DocumentGlyphStyle.SPREADSHEET_GRID -> Icons.Filled.TableChart
            DocumentGlyphStyle.SLIDE_DECK -> Icons.Filled.Slideshow
            DocumentGlyphStyle.PDF_DOCUMENT -> Icons.Filled.PictureAsPdf
            DocumentGlyphStyle.PLAIN_DOCUMENT -> Icons.Filled.Description
        }
        return FileVisualSpec(icon, badge.bottomColor)
    }
    return when (item.normalizedExt) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" ->
            FileVisualSpec(Icons.Filled.Image, FileImageColor)
        "mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "ts" ->
            FileVisualSpec(Icons.Filled.PlayCircle, FileVideoColor)
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus" ->
            FileVisualSpec(Icons.Filled.AudioFile, FileAudioColor)
        "dwg", "dxf", "dxfb", "stl", "obj", "fbx", "glb", "gltf", "stp", "step", "igs", "iges", "ifc", "3ds", "ply", "blend", "usdz" ->
            FileVisualSpec(Icons.Filled.Architecture, Color(0xFF0284C7))
        "epub", "mobi", "azw", "azw3", "cbz", "fb2", "fbz", "kf8" ->
            FileVisualSpec(Icons.Filled.AutoStories, Color(0xFF7C3AED))
        "drawio", "vdx", "graphml", "dio", "smm", "mind", "xmind", "km", "edraw", "excalidraw", "vsdu", "lt" ->
            FileVisualSpec(Icons.Filled.AccountTree, Color(0xFF0D9488))
        "psd", "psb", "ai", "xd", "sketch", "xcf", "eps", "tif", "tiff", "raw", "dng" ->
            FileVisualSpec(Icons.Filled.Brush, Color(0xFFDB2777))
        "eml", "msg", "mht", "mhtml", "emlx" ->
            FileVisualSpec(Icons.Filled.Email, Color(0xFF2563EB))
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" ->
            FileVisualSpec(Icons.Filled.FolderZip, FileArchiveColor)
        "kt", "java", "py", "js", "ts", "json", "xml", "yaml", "yml", "html", "css", "php", "sql", "sh", "go", "rs", "c", "cpp" ->
            FileVisualSpec(Icons.Filled.Code, FileCodeColor)
        else ->
            FileVisualSpec(Icons.Filled.InsertDriveFile, FileDefaultColor)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NextcloudFileListItem(
    item: KodFileItem,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    showThumbnails: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    onShareClick: (() -> Unit)? = null,
    showLocationPath: Boolean = false,
    modifier: Modifier = Modifier
) {
    val visual = resolveFileVisual(item)
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag("file_row_${item.name}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading thumbnail or crisp file-type badge (supports image, video, PDF, multi-format Office, PSD, etc.)
        val shouldTryThumb = showThumbnails && item.supportsThumbnail && !thumbnailUrl.isNullOrBlank()
        val docBadge = remember(item.isFolder, item.normalizedExt) {
            if (item.isFolder) null else resolveDocumentBadge(item.normalizedExt)
        }
        var thumbLoaded by remember(thumbnailUrl) { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if ((!thumbLoaded && docBadge != null) || (item.isFolder && item.isDepartmentFolder)) {
                        Color.Transparent
                    } else {
                        visual.tint.copy(alpha = 0.12f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!thumbLoaded) {
                when {
                    item.isFolder && item.isDepartmentFolder -> {
                        DepartmentFolderThumbnailIcon(compact = true)
                    }
                    docBadge != null -> {
                        OfficeDocumentThumbnailIcon(spec = docBadge, compact = true)
                    }
                    else -> {
                        Icon(
                            imageVector = visual.icon,
                            contentDescription = null,
                            tint = visual.tint,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
            if (shouldTryThumb) {
                AsyncImage(
                    model = thumbnailUrl,
                    imageLoader = imageLoader,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    onSuccess = { thumbLoaded = true },
                    onError = { thumbLoaded = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (thumbLoaded) 1f else 0f)
                )
            }
            if (thumbLoaded && item.isVideo) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            } else if (thumbLoaded && docBadge != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = docBadge.bottomColor.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                ) {
                    Text(
                        text = docBadge.formatTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选中",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Name + Metadata + KodBox badges (Fav, Share, Color Tags)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.isFolder && item.isDepartmentFolder) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = FileDepartmentFolderColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "部门",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FileDepartmentFolderColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                if (item.isFav) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "已收藏",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val metaText = if (item.isFolder) {
                    buildString {
                        if (item.isDepartmentFolder) append("部门空间 · ")
                        append(KodFormatters.formatTimestamp(item.modifyTime))
                        if (item.size > 0L) append(" · ${KodFormatters.formatBytes(item.size)}")
                        if (item.childCount > 0) append(" · ${item.childCount}项")
                    }
                } else {
                    "${KodFormatters.formatBytes(item.size)} · ${KodFormatters.formatTimestamp(item.modifyTime)}"
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showLocationPath || item.isRecycleItem) {
                val locationLabel = item.displayPath.ifBlank { "原目录未知" }
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "原位置：$locationLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("recycle_location_${item.name}")
                    )
                }
            }

            // Optional description note badge or inline colored tag pills (inspired by KodBox file rows)
            if (item.description.isNotBlank() || item.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.description.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "“${item.description}”",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    item.tags.take(3).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(tag.colorHex).copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.colorHex))
                                )
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isSelectionMode) {
            if (onShareClick != null) {
                IconButton(
                    onClick = onShareClick,
                    modifier = Modifier.testTag("file_share_${item.name}")
                ) {
                    if (item.isShare) {
                        val isCollabOnly = item.isCollabShare && !item.isLinkShare
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCollabOnly) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isCollabOnly) Icons.Filled.Group else Icons.Filled.Link,
                                contentDescription = if (isCollabOnly) "已开启内部协作，点击管理" else "已开启外链分享，点击管理",
                                tint = if (isCollabOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PersonAdd,
                            contentDescription = "共享与协作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.testTag("file_more_${item.name}")
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NextcloudFileGridCard(
    item: KodFileItem,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    showThumbnails: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    onShareClick: (() -> Unit)? = null,
    showLocationPath: Boolean = false,
    modifier: Modifier = Modifier
) {
    val visual = resolveFileVisual(item)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("file_grid_${item.name}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            val shouldTryThumb = showThumbnails && item.supportsThumbnail && !thumbnailUrl.isNullOrBlank()
            val docBadge = remember(item.isFolder, item.normalizedExt) {
                if (item.isFolder) null else resolveDocumentBadge(item.normalizedExt)
            }
            var thumbLoaded by remember(thumbnailUrl) { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.28f)
                    .background(visual.tint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                if (!thumbLoaded) {
                    when {
                        item.isFolder && item.isDepartmentFolder -> {
                            DepartmentFolderThumbnailIcon(compact = false)
                        }
                        docBadge != null -> {
                            OfficeDocumentThumbnailIcon(spec = docBadge, compact = false)
                        }
                        else -> {
                            Icon(
                                imageVector = visual.icon,
                                contentDescription = null,
                                tint = visual.tint,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                }
                if (shouldTryThumb) {
                    AsyncImage(
                        model = thumbnailUrl,
                        imageLoader = imageLoader,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        onSuccess = { thumbLoaded = true },
                        onError = { thumbLoaded = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (thumbLoaded) 1f else 0f)
                    )
                }
                if (thumbLoaded && item.isVideo) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "视频",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (thumbLoaded && docBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = docBadge.bottomColor.copy(alpha = 0.92f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = docBadge.formatTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                // Top badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.isFav) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "已收藏",
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(22.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            item.isFolder && item.isDepartmentFolder -> "部门文件夹"
                            item.isFolder -> "文件夹"
                            else -> KodFormatters.formatBytes(item.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if ((showLocationPath || item.isRecycleItem) && item.displayPath.isNotBlank()) {
                        Text(
                            text = item.displayPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onShareClick != null) {
                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("file_grid_share_${item.name}")
                    ) {
                        if (item.isShare) {
                            val isCollabOnly = item.isCollabShare && !item.isLinkShare
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCollabOnly) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.primaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isCollabOnly) Icons.Filled.Group else Icons.Filled.Link,
                                    contentDescription = if (isCollabOnly) "已开启内部协作，点击管理" else "已开启外链分享，点击管理",
                                    tint = if (isCollabOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.PersonAdd,
                                contentDescription = "共享与协作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyFolderState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_vault_1795282477000),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(196.dp)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(22.dp))
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ConnectionErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "无法加载云盘数据",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onRetry) {
                Text("重试连接")
            }
        }
    }
}
