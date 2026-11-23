package com.example

import com.example.model.KodFileItem
import com.example.model.ShareCancelMode
import com.example.ui.components.ModalOverlayTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KodV011ShareFolderUiFixesTest {

    @Before
    fun setUp() {
        ModalOverlayTracker.resetForTest()
    }

    @After
    fun tearDown() {
        ModalOverlayTracker.resetForTest()
    }

    @Test
    fun departmentFoldersAreDistinctAndCannotBeShared() {
        val departmentFolder = KodFileItem(
            path = "{source:20}/",
            sourceId = "20",
            name = "研发中心",
            isFolder = true,
            isDepartmentFolder = true
        )
        val regularFolder = KodFileItem(
            path = "{source:21}/",
            sourceId = "21",
            name = "项目文档",
            isFolder = true,
            isDepartmentFolder = false
        )
        val mountedIoFolder = KodFileItem(
            path = "{io:5}/team-docs/",
            sourceId = "",
            name = "team-docs",
            isFolder = true,
            isDepartmentFolder = false
        )
        val sharedDepartmentRoot = KodFileItem(
            path = "{source:2}/",
            sourceId = "2",
            name = "企业空间",
            isFolder = true,
            isDepartmentFolder = true,
            isShare = true
        )

        assertTrue(departmentFolder.isDepartmentFolder)
        assertFalse(departmentFolder.canShare)
        assertFalse(sharedDepartmentRoot.canShare)

        assertFalse(regularFolder.isDepartmentFolder)
        assertTrue(regularFolder.canShare)
        assertTrue(mountedIoFolder.canShare)
    }

    @Test
    fun modalOverlayTrackerTracksNestedDialogsAndClampsAtZero() {
        assertFalse(ModalOverlayTracker.hasActiveModal())

        // Opening first dialog (e.g. FileActionBottomSheet)
        ModalOverlayTracker.enter()
        assertTrue(ModalOverlayTracker.hasActiveModal())

        // Opening nested dialog (e.g. KodShareConfigDialog)
        ModalOverlayTracker.enter()
        assertTrue(ModalOverlayTracker.hasActiveModal())

        // Closing one still keeps active modal tracked
        ModalOverlayTracker.exit()
        assertTrue(ModalOverlayTracker.hasActiveModal())

        // Closing final dialog clears active modal
        ModalOverlayTracker.exit()
        assertFalse(ModalOverlayTracker.hasActiveModal())

        // Extra exit calls never drop counter below zero
        ModalOverlayTracker.exit()
        assertFalse(ModalOverlayTracker.hasActiveModal())
        ModalOverlayTracker.enter()
        assertTrue(ModalOverlayTracker.hasActiveModal())
    }

    @Test
    fun shareCancelModesCoverAllExternalAndInternalTargets() {
        assertEquals(
            setOf(
                ShareCancelMode.ALL,
                ShareCancelMode.EXTERNAL_LINK_ONLY,
                ShareCancelMode.INTERNAL_COLLAB_ONLY
            ),
            ShareCancelMode.entries.toSet()
        )
    }
}
