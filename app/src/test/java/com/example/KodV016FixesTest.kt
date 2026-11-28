package com.example

import com.example.model.KodExistingShareDetail
import com.example.model.KodFileItem
import com.example.ui.screens.shouldAutoEnterFullscreenPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV016FixesTest {

    @Test
    fun pdfAndOfficePluginPreviewsAutoEnterFullscreen() {
        val pdf = KodFileItem(path = "{source:101}", name = "manual.pdf", isFolder = false, ext = "pdf")
        val docx = KodFileItem(path = "{source:102}", name = "proposal.docx", isFolder = false, ext = "docx")
        val xlsx = KodFileItem(path = "{source:103}", name = "budget.xlsx", isFolder = false, ext = "xlsx")
        val pptx = KodFileItem(path = "{source:104}", name = "slides.pptx", isFolder = false, ext = "pptx")
        val wps = KodFileItem(path = "{source:105}", name = "draft.wps", isFolder = false, ext = "wps")
        val epub = KodFileItem(path = "{source:106}", name = "book.epub", isFolder = false, ext = "epub")
        val dwg = KodFileItem(path = "{source:107}", name = "floorplan.dwg", isFolder = false, ext = "dwg")

        listOf(pdf, docx, xlsx, pptx, wps, epub, dwg).forEach { doc ->
            assertTrue("Expected auto-fullscreen for ${doc.name}", shouldAutoEnterFullscreenPreview(doc, usePluginViewer = true))
            assertFalse("Should not auto-fullscreen without plugin viewer for ${doc.name}", shouldAutoEnterFullscreenPreview(doc, usePluginViewer = false))
        }
    }

    @Test
    fun imageVideoAudioAndPlainTextDoNotAutoEnterFullscreen() {
        val png = KodFileItem(path = "{source:201}", name = "photo.png", isFolder = false, ext = "png")
        val mp4 = KodFileItem(path = "{source:202}", name = "clip.mp4", isFolder = false, ext = "mp4")
        val flac = KodFileItem(path = "{source:203}", name = "track.flac", isFolder = false, ext = "flac")
        val txt = KodFileItem(path = "{source:204}", name = "notes.txt", isFolder = false, ext = "txt")

        listOf(png, mp4, flac, txt).forEach { item ->
            assertFalse("Did not expect auto-fullscreen for ${item.name}", shouldAutoEnterFullscreenPreview(item, usePluginViewer = true))
            assertFalse("Did not expect auto-fullscreen for ${item.name}", shouldAutoEnterFullscreenPreview(item, usePluginViewer = false))
        }
    }

    @Test
    fun fileItemAndExistingShareDetailPreserveLinkCollabAndOwnerMetadata() {
        val collabOnlyFolder = KodFileItem(
            path = "{source:301}/",
            sourceId = "301",
            name = "共享资源",
            isFolder = true,
            isShare = true,
            isLinkShare = false,
            isCollabShare = true,
            shareId = "88",
            shareOwnerUserId = "1",
            shareOwnerName = "系统管理员"
        )
        assertTrue(collabOnlyFolder.isShare)
        assertFalse(collabOnlyFolder.isLinkShare)
        assertTrue(collabOnlyFolder.isCollabShare)
        assertEquals("88", collabOnlyFolder.shareId)
        assertEquals("1", collabOnlyFolder.shareOwnerUserId)
        assertEquals("系统管理员", collabOnlyFolder.shareOwnerName)

        val detail = KodExistingShareDetail(
            shareId = "88",
            isLink = false,
            isShareTo = true,
            ownerUserId = "1",
            ownerName = "系统管理员"
        )
        assertFalse(detail.isLink)
        assertTrue(detail.isShareTo)
        assertEquals("1", detail.ownerUserId)
        assertEquals("系统管理员", detail.ownerName)
    }
}
