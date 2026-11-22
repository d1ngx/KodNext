package com.example

import com.example.model.KodFileItem
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.parseMarkdownBlocks
import com.example.viewmodel.KodFilesCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryReadmePreviewTest {

    private fun fakeItem(name: String, isFolder: Boolean = false): KodFileItem {
        val ext = if (isFolder) "" else name.substringAfterLast('.', "").lowercase()
        return KodFileItem(
            path = "/demo/$name",
            name = name,
            isFolder = isFolder,
            size = if (isFolder) 0L else 128L,
            ext = ext
        )
    }

    @Test
    fun detectsReadmeMdCaseInsensitivelyAndIgnoresFolders() {
        val items = listOf(
            fakeItem("README.md", isFolder = true),
            fakeItem("notes.txt"),
            fakeItem("ReadMe.MD"),
            fakeItem("readme.markdown")
        )
        val found = KodFilesCoordinator.findDirectoryReadme(items)
        assertEquals("ReadMe.MD", found?.name)
    }

    @Test
    fun fallsBackToReadmeMarkdownWhenReadmeMdAbsent() {
        val items = listOf(
            fakeItem("design.pdf"),
            fakeItem("README.markdown")
        )
        val found = KodFilesCoordinator.findDirectoryReadme(items)
        assertEquals("README.markdown", found?.name)
    }

    @Test
    fun returnsNullWhenNoReadmeInDirectory() {
        val items = listOf(
            fakeItem("guide.md"),
            fakeItem("changelog.md"),
            fakeItem("src", isFolder = true)
        )
        assertNull(KodFilesCoordinator.findDirectoryReadme(items))
    }

    @Test
    fun parsesMarkdownBlocksCorrectly() {
        val md = """
            # 项目说明

            这是一个测试目录。

            - 第一项
            - 第二项
            1. 步骤一

            > 注意事项

            ```kotlin
            println("hello")
            ```

            ---
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals("项目说明", (blocks[0] as MarkdownBlock.Heading).text)

        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertEquals("这是一个测试目录。", (blocks[1] as MarkdownBlock.Paragraph).text)

        assertTrue(blocks[2] is MarkdownBlock.BulletItem)
        assertEquals("第一项", (blocks[2] as MarkdownBlock.BulletItem).text)

        assertTrue(blocks[3] is MarkdownBlock.BulletItem)
        assertEquals("第二项", (blocks[3] as MarkdownBlock.BulletItem).text)

        assertTrue(blocks[4] is MarkdownBlock.BulletItem)
        assertEquals("1.", (blocks[4] as MarkdownBlock.BulletItem).marker)
        assertEquals("步骤一", (blocks[4] as MarkdownBlock.BulletItem).text)

        assertTrue(blocks[5] is MarkdownBlock.BlockQuote)
        assertEquals("注意事项", (blocks[5] as MarkdownBlock.BlockQuote).text)

        assertTrue(blocks[6] is MarkdownBlock.CodeBlock)
        assertEquals("kotlin", (blocks[6] as MarkdownBlock.CodeBlock).language)
        assertEquals("println(\"hello\")", (blocks[6] as MarkdownBlock.CodeBlock).code)

        assertTrue(blocks[7] is MarkdownBlock.HorizontalRule)
    }
}
