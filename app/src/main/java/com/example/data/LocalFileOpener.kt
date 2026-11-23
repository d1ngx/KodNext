package com.example.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Play-Policy compliant local downloaded file opener & sharer using Android [FileProvider].
 *
 * Properly attaches [ClipData] and [Intent.FLAG_GRANT_READ_URI_PERMISSION] on both the target
 * intent and chooser (critical on Android 11–16 / OEM ROMs), resolves MIME types with
 * [MimeTypeMap] fallback, and retries with wildcard MIME when no specific app handles an extension.
 */
object LocalFileOpener {

    sealed interface OpenOutcome {
        data object Opened : OpenOutcome
        data class Failed(val message: String) : OpenOutcome
    }

    /**
     * Resolves an existing downloaded file, checking [recordedLocalPath] first and falling back
     * to the app's confined `filesDir/downloads/<sanitizedFileName>` directory.
     */
    fun resolveExistingDownloadFile(
        context: Context,
        recordedLocalPath: String,
        fileName: String
    ): File? {
        if (recordedLocalPath.isNotBlank()) {
            val direct = File(recordedLocalPath)
            if (direct.exists() && direct.isFile) return direct
        }
        if (fileName.isNotBlank()) {
            val downloadsDir = File(context.filesDir, "downloads")
            val confined = KodFormatters.safeConfinedFile(downloadsDir, fileName)
            if (confined.exists() && confined.isFile) return confined
        }
        return null
    }

    fun resolveMimeType(file: File, hintMimeType: String = ""): String {
        val ext = file.extension.lowercase(Locale.US)
        val fromHint = hintMimeType.trim()
        if (fromHint.isNotBlank() && fromHint != "*/*" && fromHint != "application/octet-stream") {
            return fromHint
        }
        val fromFormatter = KodFormatters.mimeTypeForExtension(ext)
        if (fromFormatter != "*/*") return fromFormatter
        if (ext.isNotBlank()) {
            val fromSystem = runCatching {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }.getOrNull()
            if (!fromSystem.isNullOrBlank()) return fromSystem
        }
        return "*/*"
    }

    fun buildViewIntent(context: Context, file: File, mimeType: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openFile(context: Context, file: File, hintMimeType: String = ""): OpenOutcome {
        if (!file.exists() || !file.isFile) {
            return OpenOutcome.Failed("本地文件不存在，请重新下载")
        }
        val resolvedMime = resolveMimeType(file, hintMimeType)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            return OpenOutcome.Failed("无法生成本地文件访问授权，请重新下载后重试")
        }

        // Attempt 1: Open with exact resolved MIME type via chooser (with explicit read grant & ClipData)
        val exactAttempt = runCatching {
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(targetIntent, "打开 ${file.name}").apply {
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
        if (exactAttempt.isSuccess) return OpenOutcome.Opened

        // Attempt 2: Retry with wildcard */* if specific MIME had no matching handler
        if (resolvedMime != "*/*") {
            val wildcardAttempt = runCatching {
                val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(targetIntent, "选择应用打开 ${file.name}").apply {
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
            if (wildcardAttempt.isSuccess) return OpenOutcome.Opened
        }

        val err = exactAttempt.exceptionOrNull()
        return if (err is ActivityNotFoundException) {
            OpenOutcome.Failed("设备上未安装可打开 .${file.extension.ifBlank { "此类型" }} 文件的应用，可尝试点击右侧分享按钮发送至其他应用")
        } else {
            OpenOutcome.Failed("打开本地文件失败：${err?.message ?: "未知错误"}")
        }
    }

    fun shareFile(context: Context, file: File, hintMimeType: String = ""): OpenOutcome {
        if (!file.exists() || !file.isFile) {
            return OpenOutcome.Failed("本地文件不存在，请重新下载")
        }
        val resolvedMime = resolveMimeType(file, hintMimeType)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            return OpenOutcome.Failed("无法生成本地文件分享授权")
        }
        return runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "分享或用其他应用打开 ${file.name}").apply {
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            OpenOutcome.Opened
        }.getOrElse {
            OpenOutcome.Failed("无法调起系统分享菜单")
        }
    }
}
