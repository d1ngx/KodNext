package com.example

import androidx.compose.ui.graphics.Color
import com.example.data.KodBoxApiClient
import com.example.model.KodCollabAssignment
import com.example.model.KodCollabTarget
import com.example.model.KodCollabTargetType
import com.example.model.KodSpace
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.formatInlineMarkdown
import com.example.ui.components.parseMarkdownBlocks
import com.example.ui.screens.orderTransfersForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV006ImprovementsTest {

    @Test
    fun `orderTransfersForDisplay pins active RUNNING tasks above finished tasks`() {
        val tasks = listOf(
            TransferTask(
                id = "done_new",
                fileName = "finished.pdf",
                remotePath = "{source:1}/",
                type = TransferType.UPLOAD,
                status = TransferStatus.COMPLETED,
                timestamp = 5000L
            ),
            TransferTask(
                id = "run_old",
                fileName = "uploading_1.mp4",
                remotePath = "{source:1}/",
                type = TransferType.UPLOAD,
                status = TransferStatus.RUNNING,
                timestamp = 2000L
            ),
            TransferTask(
                id = "run_new",
                fileName = "uploading_2.mp4",
                remotePath = "{source:1}/",
                type = TransferType.UPLOAD,
                status = TransferStatus.RUNNING,
                timestamp = 4000L
            ),
            TransferTask(
                id = "fail_old",
                fileName = "failed.zip",
                remotePath = "{source:1}/",
                type = TransferType.DOWNLOAD,
                status = TransferStatus.FAILED,
                timestamp = 3000L
            )
        )

        val ordered = orderTransfersForDisplay(tasks)
        assertEquals(listOf("run_new", "run_old", "done_new", "fail_old"), ordered.map { it.id })
    }

    @Test
    fun `enterprise top-level space label is renamed to 共享空间`() {
        assertEquals("共享空间", KodSpace.ENTERPRISE.label)
        assertEquals("{block:enterprise}/", KodSpace.ENTERPRISE.virtualPath)
    }

    @Test
    fun `buildCollabAuthToJson serializes deduplicated user and department targets for KodBox`() {
        val assignments = listOf(
            KodCollabAssignment(
                target = KodCollabTarget(KodCollabTargetType.USER, "12", "张三"),
                authId = "2",
                authName = "编辑协作"
            ),
            KodCollabAssignment(
                target = KodCollabTarget(KodCollabTargetType.GROUP, "3", "研发部"),
                authId = "3",
                authName = "查看下载"
            ),
            KodCollabAssignment(
                target = KodCollabTarget(KodCollabTargetType.USER, "12", "张三"),
                authId = "4",
                authName = "仅预览"
            )
        )

        val json = KodBoxApiClient.buildCollabAuthToJson(assignments)
        assertEquals(
            """[{"targetType":"1","targetID":"12","authID":"2"},{"targetType":"2","targetID":"3","authID":"3"}]""",
            json
        )
    }

    @Test
    fun `normalizeDisplayLocation cleans original parent folder for recycle bin items`() {
        assertEquals(
            "个人空间 / 财务归档 / 2026",
            KodBoxApiClient.normalizeDisplayLocation("个人空间/财务归档/2026/Q4预算表.xlsx", "Q4预算表.xlsx")
        )
        assertEquals(
            "共享空间 / 设计部",
            KodBoxApiClient.normalizeDisplayLocation("/共享空间/设计部/设计规范.pdf", "设计规范.pdf")
        )
        assertEquals(
            "个人空间 / 项目文档",
            KodBoxApiClient.normalizeDisplayLocation("{source:1}/个人空间/项目文档/README.md", "README.md")
        )
        assertEquals(
            "",
            KodBoxApiClient.normalizeDisplayLocation("null", "demo.txt")
        )
    }

    @Test
    fun `parseMarkdownBlocks parses HTML h1-h6 headings, lists, blockquotes, hr, code blocks, and comments`() {
        val readme = """
            <!-- hidden html comment -->
            <h1 align="center">KodNext 项目说明</h1>
            <h2 id="intro">快速开始 &amp; 安装</h2>
            <p>支持 <strong>加粗</strong>、<em>斜体</em> 与 <a href="https://example.com">官方文档</a>。<br/>第二行说明</p>
            <ul>
              <li>列表条目一</li>
              <li>列表条目二</li>
            </ul>
            <blockquote>重要提示：请先备份配置</blockquote>
            <hr />
            <pre><code class="language-bash">echo "&lt;h2&gt;literal&lt;/h2&gt;"</code></pre>
            ```html
            <h2>fenced literal heading</h2>
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(readme)

        val h1 = blocks.filterIsInstance<MarkdownBlock.Heading>().first { it.level == 1 }
        assertEquals("KodNext 项目说明", h1.text)

        val h2 = blocks.filterIsInstance<MarkdownBlock.Heading>().first { it.level == 2 }
        assertEquals("快速开始 &amp; 安装", h2.text)
        assertEquals("快速开始 & 安装", formatInlineMarkdown(h2.text, Color.Blue, Color.Gray).text)

        val paragraph = blocks.filterIsInstance<MarkdownBlock.Paragraph>().first()
        val renderedParagraph = formatInlineMarkdown(paragraph.text, Color.Blue, Color.Gray).text
        assertTrue(renderedParagraph.contains("支持 加粗、斜体 与 官方文档。"))
        assertFalse(renderedParagraph.contains("<strong>"))
        assertFalse(renderedParagraph.contains("<a "))

        val bullets = blocks.filterIsInstance<MarkdownBlock.BulletItem>()
        assertEquals(listOf("列表条目一", "列表条目二"), bullets.map { it.text })

        val quote = blocks.filterIsInstance<MarkdownBlock.BlockQuote>().first()
        assertEquals("重要提示：请先备份配置", quote.text)

        assertTrue(blocks.any { it is MarkdownBlock.HorizontalRule })

        val codeBlocks = blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(2, codeBlocks.size)
        assertEquals("bash", codeBlocks[0].language)
        assertEquals("echo \"<h2>literal</h2>\"", codeBlocks[0].code)
        assertEquals("html", codeBlocks[1].language)
        assertEquals("<h2>fenced literal heading</h2>", codeBlocks[1].code)
    }
}
