package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.model.KodFileItem
import com.example.ui.theme.JetBrainsMonoFamily

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val marker: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

internal fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphBuffer.joinToString("\n")))
            paragraphBuffer.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block ```
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("""^([-*_])\1{2,}$"""))) {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // Heading (# .. ######)
        val headingMatch = Regex("""^(#{1,6})\s+(.+)$""").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trimEnd('#', ' ')
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // Blockquote (> ...)
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.BlockQuote(quoteLines.joinToString("\n")))
            continue
        }

        // Unordered list (- / * / +)
        val bulletMatch = Regex("""^[-*+]\s+(.+)$""").find(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.BulletItem("•", bulletMatch.groupValues[1].trim()))
            i++
            continue
        }

        // Ordered list (1. ...)
        val orderedMatch = Regex("""^(\d+\.)\s+(.+)$""").find(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.BulletItem(orderedMatch.groupValues[1], orderedMatch.groupValues[2].trim()))
            i++
            continue
        }

        paragraphBuffer.add(trimmed)
        i++
    }
    flushParagraph()
    return blocks
}

/**
 * Clean, collapsible inline README.md preview card rendered automatically in Files tab
 * whenever the current directory contains a `README.md` / `readme.md` file.
 * All other files (including README.md itself) remain browsable in the regular file list below.
 */
@Composable
fun DirectoryReadmeCard(
    item: KodFileItem,
    markdownContent: String,
    isLoading: Boolean,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenFullPreview: (KodFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val maxInlineBlocks = 26
    val allBlocks = remember(markdownContent) { parseMarkdownBlocks(markdownContent) }
    val visibleBlocks = remember(allBlocks) { allBlocks.take(maxInlineBlocks) }
    val isTruncated = allBlocks.size > maxInlineBlocks

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("directory_readme_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "自动预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onOpenFullPreview(item) },
                    modifier = Modifier.testTag("readme_open_full_button")
                ) {
                    Text("全屏", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "全屏阅读或编辑",
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.testTag("readme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "收起目录说明" else "展开目录说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        isLoading -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "正在加载 ${item.name} 预览…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        visibleBlocks.isEmpty() -> {
                            Text(
                                text = if (item.size > 512L * 1024L) {
                                    "该说明文档体积较大，点击右上角「全屏」查看完整内容。"
                                } else {
                                    "当前 README 文档暂无可预览文本内容。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            visibleBlocks.forEach { block ->
                                MarkdownBlockView(block = block)
                            }
                            if (isTruncated) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                TextButton(
                                    onClick = { onOpenFullPreview(item) },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("展开阅读完整 ${item.name} →")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                3 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = formatInlineMarkdown(block.text, primaryColor, codeBg),
                    style = style,
                    fontWeight = FontWeight.Bold,
                    color = onSurface
                )
                if (block.level <= 2) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }
            }
        }

        is MarkdownBlock.Paragraph -> {
            Text(
                text = formatInlineMarkdown(block.text, primaryColor, codeBg),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurface
            )
        }

        is MarkdownBlock.BulletItem -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = block.marker,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
                Text(
                    text = formatInlineMarkdown(block.text, primaryColor, codeBg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.BlockQuote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(primaryColor.copy(alpha = 0.7f))
                )
                Text(
                    text = formatInlineMarkdown(block.text, primaryColor, codeBg),
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (block.language.isNotBlank()) {
                        Text(
                            text = block.language,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = onSurface
                    )
                }
            }
        }

        MarkdownBlock.HorizontalRule -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

private fun formatInlineMarkdown(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color
): AnnotatedString {
    val tokenRegex = Regex("""(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__|\[[^\]]+]\([^)]+\)|\*[^*\s][^*]*\*)""")
    return buildAnnotatedString {
        var cursor = 0
        for (match in tokenRegex.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFamily,
                            background = codeBg
                        )
                    ) {
                        append(" ${token.substring(1, token.length - 1)} ")
                    }
                }
                (token.startsWith("**") && token.endsWith("**")) || (token.startsWith("__") && token.endsWith("__")) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("[") && token.contains("](") -> {
                    val label = token.substringAfter("[").substringBefore("](")
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(label)
                    }
                }
                token.startsWith("*") && token.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
