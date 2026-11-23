package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object KodFormatters {

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[digitGroups])
    }

    fun formatTimestamp(epochSecondsOrMillis: Long): String {
        if (epochSecondsOrMillis <= 0L) return "—"
        val millis = if (epochSecondsOrMillis < 10_000_000_000L) {
            epochSecondsOrMillis * 1000L
        } else {
            epochSecondsOrMillis
        }
        val now = System.currentTimeMillis()
        val diff = now - millis
        return when {
            diff in 0 until 60_000L -> "刚刚"
            diff in 60_000L until 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff in 3_600_000L until 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff in 86_400_000L until 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }
    }

    fun formatDateOnly(epochSecondsOrMillis: Long): String {
        if (epochSecondsOrMillis <= 0L) return "永久有效"
        val millis = if (epochSecondsOrMillis < 10_000_000_000L) {
            epochSecondsOrMillis * 1000L
        } else {
            epochSecondsOrMillis
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }

    /**
     * Strictly validates and normalizes user-entered private KodBox server URLs.
     * Allows only http:// or https:// schemes with a valid host.
     */
    fun normalizeServerUrl(rawInput: String): Result<String> {
        val trimmed = rawInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入服务器地址"))
        }
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return Result.failure(IllegalArgumentException("不支持的地址协议，请使用 https:// 或 http://"))
        }
        val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("服务器地址格式无效，请检查域名、IP 或端口"))

        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return Result.failure(IllegalArgumentException("仅支持 HTTP 或 HTTPS 协议"))
        }
        if (parsed.host.isBlank()) {
            return Result.failure(IllegalArgumentException("服务器主机名不能为空"))
        }
        val cleaned = parsed.toString().removeSuffix("/index.php").trimEnd('/')
        return Result.success(cleaned)
    }

    /**
     * Sanitizes downloaded filenames and guarantees destination stays confined to baseDir.
     */
    fun safeConfinedFile(baseDir: File, rawFileName: String): File {
        if (!baseDir.exists()) baseDir.mkdirs()
        val sanitized = rawFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "_")
            .replace(Regex("[\\r\\n\\t:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "download_${System.currentTimeMillis()}" }
        var candidate = File(baseDir, sanitized)
        val baseCanonical = baseDir.canonicalPath
        if (!candidate.canonicalPath.startsWith(baseCanonical + File.separator)) {
            candidate = File(baseDir, "file_${System.currentTimeMillis()}")
        }
        return candidate
    }

    fun tagColorForStyle(style: String): Long {
        val lower = style.lowercase(Locale.US)
        return when {
            lower.contains("red") || lower.contains("pink") -> 0xFFEF4444L
            lower.contains("orange") -> 0xFFF97316L
            lower.contains("yellow") || lower.contains("amber") -> 0xFFEAB308L
            lower.contains("green") -> 0xFF22C55EL
            lower.contains("cyan") || lower.contains("teal") -> 0xFF06B6D4L
            lower.contains("purple") || lower.contains("violet") -> 0xFF8B5CF6L
            lower.contains("grey") || lower.contains("gray") -> 0xFF64748BL
            else -> 0xFF0074C8L
        }
    }

    fun mimeTypeForExtension(ext: String): String {
        return when (ext.trim().trimStart('.').lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "ico" -> "image/x-icon"
            "tif", "tiff" -> "image/tiff"
            "svg" -> "image/svg+xml"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wma" -> "audio/x-ms-wma"
            "amr" -> "audio/amr"
            "pdf" -> "application/pdf"
            "ofd" -> "application/ofd"
            "epub" -> "application/epub+zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "rtf" -> "application/rtf"
            "wps" -> "application/vnd.ms-works"
            "et" -> "application/vnd.ms-excel"
            "dps" -> "application/vnd.ms-powerpoint"
            "txt", "md", "markdown", "log", "csv", "ini", "conf", "yaml", "yml",
            "kt", "java", "py", "js", "jsx", "tsx", "sh", "sql", "c", "cpp", "h", "css" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            "bz2" -> "application/x-bzip2"
            "xz" -> "application/x-xz"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }
}
