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
import androidx.compose.ui.graphics.Color
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

private val HTML_COMMENT_REGEX = Regex("""<!--[\s\S]*?-->""")
private val HTML_PRE_CODE_REGEX = Regex(
    """<pre\b[^>]*>\s*(?:<code\b([^>]*)>)?([\s\S]*?)(?:</code>)?\s*</pre>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_HEADING_REGEX = Regex(
    """<h([1-6])\b[^>]*>([\s\S]*?)</h\1>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_HR_REGEX = Regex("""<hr\b[^>]*/?>""", setOf(RegexOption.IGNORE_CASE))
private val HTML_BLOCKQUOTE_REGEX = Regex(
    """<blockquote\b[^>]*>([\s\S]*?)</blockquote>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_LI_REGEX = Regex(
    """<li\b[^>]*>([\s\S]*?)</li>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_P_REGEX = Regex(
    """<p\b[^>]*>([\s\S]*?)</p>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_SUMMARY_REGEX = Regex(
    """<summary\b[^>]*>([\s\S]*?)</summary>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_BR_REGEX = Regex("""<br\b[^>]*/?>""", setOf(RegexOption.IGNORE_CASE))
private val HTML_CONTAINER_BLOCK_TAG_REGEX = Regex(
    """</?(?:div|center|section|article|header|footer|main|nav|ul|ol|details|figure|figcaption|table|thead|tbody|tr)\b[^>]*>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_TABLE_CELL_REGEX = Regex(
    """</?(?:td|th)\b[^>]*>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_INLINE_WRAPPER_TAG_REGEX = Regex(
    """</?(?:span|font|small|sub|sup|mark|kbd|abbr)\b[^>]*>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_IMG_REGEX = Regex(
    """<img\b[^>]*>""",
    setOf(RegexOption.IGNORE_CASE)
)
private val HTML_ATTR_ALT_REGEX = Regex("""\balt\s*=\s*["']([^"']*)["']""", setOf(RegexOption.IGNORE_CASE))
private val HTML_CODE_LANG_REGEX = Regex("""language-([A-Za-z0-9_+#-]+)""", setOf(RegexOption.IGNORE_CASE))

internal fun decodeHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    return text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&copy;", "©")
        .replace("&reg;", "®")
        .replace("&hellip;", "…")
        .replace("&amp;", "&")
}

private fun collapseInlineWhitespace(raw: String): String {
    return raw.replace(HTML_BR_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/**
 * Normalizes common HTML tags embedded inside README.md files (e.g. `<h2>`, `<h1 align="center">`,
 * `<p>`, `<br/>`, `<hr>`, `<blockquote>`, `<ul><li>`, `<pre><code>`, `<!-- comment -->`)
 * into structured Markdown blocks while preserving fenced code blocks verbatim.
 */
internal fun preprocessHtmlMarkdown(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split("\n")
    val out = StringBuilder()
    val outsideBuffer = StringBuilder()
    var inFence = false

    fun flushOutside() {
        if (outsideBuffer.isEmpty()) return
        out.append(convertHtmlSegmentToMarkdown(outsideBuffer.toString()))
        outsideBuffer.setLength(0)
    }

    for (idx in lines.indices) {
        val line = lines[idx]
        val isFenceBoundary = line.trim().startsWith("```")
        if (isFenceBoundary) {
            if (!inFence) {
                flushOutside()
                inFence = true
                out.append(line)
                if (idx < lines.lastIndex) out.append('\n')
            } else {
                out.append(line)
                if (idx < lines.lastIndex) out.append('\n')
                inFence = false
            }
        } else if (inFence) {
            out.append(line)
            if (idx < lines.lastIndex) out.append('\n')
        } else {
            outsideBuffer.append(line)
            if (idx < lines.lastIndex) outsideBuffer.append('\n')
        }
    }
    flushOutside()
    return out.toString()
}

private fun convertHtmlSegmentToMarkdown(segment: String): String {
    if (!segment.contains('<')) return decodeHtmlEntities(segment)

    // Protect <pre><code> blocks before processing other HTML tags
    val placeholders = mutableListOf<String>()
    var text = HTML_COMMENT_REGEX.replace(segment, "")

    text = HTML_PRE_CODE_REGEX.replace(text) { m ->
        val attrs = m.groupValues[1]
        val lang = HTML_CODE_LANG_REGEX.find(attrs)?.groupValues?.get(1).orEmpty()
        val codeBody = decodeHtmlEntities(m.groupValues[2].trim('\n'))
        val fenced = "\n```$lang\n$codeBody\n```\n"
        val token = "@@HTML_CODE_BLOCK_${placeholders.size}@@"
        placeholders.add(fenced)
        token
    }

    text = HTML_HEADING_REGEX.replace(text) { m ->
        val level = m.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 2
        val inner = collapseInlineWhitespace(m.groupValues[2])
        if (inner.isBlank()) "\n" else "\n${"#".repeat(level)} $inner\n\n"
    }

    text = HTML_HR_REGEX.replace(text, "\n---\n")

    text = HTML_BLOCKQUOTE_REGEX.replace(text) { m ->
        val innerLines = m.groupValues[1]
            .replace(HTML_BR_REGEX, "\n")
            .replace(HTML_P_REGEX) { pm -> "\n${pm.groupValues[1].trim()}\n" }
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (innerLines.isEmpty()) "\n" else "\n" + innerLines.joinToString("\n") { "> $it" } + "\n\n"
    }

    text = HTML_LI_REGEX.replace(text) { m ->
        val inner = collapseInlineWhitespace(m.groupValues[1])
        if (inner.isBlank()) "\n" else "\n- $inner\n"
    }

    text = HTML_SUMMARY_REGEX.replace(text) { m ->
        val inner = collapseInlineWhitespace(m.groupValues[1])
        if (inner.isBlank()) "\n" else "\n**$inner**\n\n"
    }

    text = HTML_P_REGEX.replace(text) { m ->
        val inner = m.groupValues[1].replace(HTML_BR_REGEX, "\n").trim()
        if (inner.isBlank()) "\n" else "\n$inner\n\n"
    }

    text = HTML_BR_REGEX.replace(text, "\n")
    text = HTML_CONTAINER_BLOCK_TAG_REGEX.replace(text, "\n")
    text = HTML_TABLE_CELL_REGEX.replace(text, " ")
    text = HTML_IMG_REGEX.replace(text) { m ->
        val alt = HTML_ATTR_ALT_REGEX.find(m.value)?.groupValues?.get(1)?.trim().orEmpty()
        if (alt.isNotBlank()) "[$alt]" else ""
    }
    text = HTML_INLINE_WRAPPER_TAG_REGEX.replace(text, "")

    placeholders.forEachIndexed { index, block ->
        text = text.replace("@@HTML_CODE_BLOCK_${index}@@", block)
    }

    return text
}

internal fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val preprocessed = preprocessHtmlMarkdown(raw)
    val lines = preprocessed.split("\n")
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

/**
 * Full rendered Markdown + HTML document view reusable in FilePreviewScreen for `.md` files.
 */
@Composable
fun MarkdownDocumentContent(
    markdownContent: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdownContent) { parseMarkdownBlocks(markdownContent) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (blocks.isEmpty()) {
            Text(
                text = "（空文档）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            blocks.forEach { block ->
                MarkdownBlockView(block = block)
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

private val INLINE_TOKEN_REGEX = Regex(
    """(`[^`]+`|<code\b[^>]*>.*?</code>|\*\*[^*]+\*\*|__[^_]+__|<(?:strong|b)\b[^>]*>.*?</(?:strong|b)>|<(?:em|i)\b[^>]*>.*?</(?:em|i)>|<(?:del|s|strike)\b[^>]*>.*?</(?:del|s|strike)>|~~[^~]+~~|<u\b[^>]*>.*?</u>|<a\b[^>]*>.*?</a>|\[[^\]]+]\([^)]+\)|\*[^*\s][^*]*\*)""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val HTML_TAG_STRIP_REGEX = Regex("""<[^>]+>""")

internal fun stripResidualHtmlTags(text: String): String {
    return decodeHtmlEntities(HTML_TAG_STRIP_REGEX.replace(text, ""))
}

internal fun formatInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBg: Color
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE_TOKEN_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                append(stripResidualHtmlTags(text.substring(cursor, match.range.first)))
            }
            val token = match.value
            val lower = token.lowercase()
            when {
                token.startsWith("`") && token.endsWith("`") -> {
                    withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, background = codeBg)) {
                        append(" ${decodeHtmlEntities(token.substring(1, token.length - 1))} ")
                    }
                }
                lower.startsWith("<code") -> {
                    val inner = decodeHtmlEntities(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, background = codeBg)) {
                        append(" $inner ")
                    }
                }
                (token.startsWith("**") && token.endsWith("**")) || (token.startsWith("__") && token.endsWith("__")) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(stripResidualHtmlTags(token.substring(2, token.length - 2)))
                    }
                }
                lower.startsWith("<strong") || lower.startsWith("<b") -> {
                    val inner = stripResidualHtmlTags(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inner)
                    }
                }
                lower.startsWith("<em") || lower.startsWith("<i") -> {
                    val inner = stripResidualHtmlTags(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inner)
                    }
                }
                lower.startsWith("<del") || lower.startsWith("<s") || lower.startsWith("<strike") -> {
                    val inner = stripResidualHtmlTags(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(inner)
                    }
                }
                token.startsWith("~~") && token.endsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(stripResidualHtmlTags(token.substring(2, token.length - 2)))
                    }
                }
                lower.startsWith("<u") -> {
                    val inner = stripResidualHtmlTags(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(inner)
                    }
                }
                lower.startsWith("<a") -> {
                    val inner = stripResidualHtmlTags(token.substringAfter(">").substringBeforeLast("<"))
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(inner)
                    }
                }
                token.startsWith("[") && token.contains("](") -> {
                    val label = stripResidualHtmlTags(token.substringAfter("[").substringBefore("]("))
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
                        append(stripResidualHtmlTags(token.substring(1, token.length - 1)))
                    }
                }
                else -> append(stripResidualHtmlTags(token))
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(stripResidualHtmlTags(text.substring(cursor)))
        }
    }
}
