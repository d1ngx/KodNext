package com.example

import com.example.data.KodBoxApiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodV013ShareCancelAndDeptIconTest {

    @Test
    fun departmentContainerPathsCoverMyDepartmentsAndEnterpriseBuckets() {
        val departmentContainers = listOf(
            "{block:enterprise}/",
            "{block:enterprise}",
            "{groupRootSelf}/",
            "{group:self}/",
            "{block:groupRootSelf}/",
            "{block:group}/",
            "{groupRoot}/",
            "{groupRootAll}/",
            "{groupSelf}/",
            "{groupAll}/",
            "{groupPath}/",
            "{group:12}/"
        )
        departmentContainers.forEach { path ->
            assertTrue("Expected department container path: $path", KodBoxApiClient.isDepartmentContainerPath(path))
        }

        val nonContainers = listOf(
            "",
            "{source:12770}/",
            "{io:5}/team-docs/",
            "{userFav}/",
            "{userRecycle}/",
            "{shareToMe}/",
            "{userShare}/",
            "{userFileType:image}/",
            "{block:fileType}/",
            "{block:fileTag}/",
            "{user:1}/"
        )
        nonContainers.forEach { path ->
            assertFalse("Did not expect department container path: $path", KodBoxApiClient.isDepartmentContainerPath(path))
        }
    }

    @Test
    fun departmentContainerTitlesMatchOnlyVirtualDepartmentBuckets() {
        val matchingTitles = listOf(
            "我所在的部门",
            "所在部门",
            "我的部门",
            "所有部门",
            "全部部门",
            "部门列表",
            "My Departments",
            "All Departments"
        )
        matchingTitles.forEach { title ->
            assertTrue("Expected department container title: $title", KodBoxApiClient.isDepartmentContainerTitle(title))
        }

        // Concrete company root or regular folders inside a department must NOT be matched as all-department containers
        val regularOrMixedTitles = listOf(
            "",
            "企业网盘",
            "共享空间",
            "共享资源",
            "其他",
            "文档",
            "个人空间",
            "项目文档",
            "研发部",
            "收藏夹",
            "回收站"
        )
        regularOrMixedTitles.forEach { title ->
            assertFalse("Did not expect department container title: $title", KodBoxApiClient.isDepartmentContainerTitle(title))
        }
    }

    @Test
    fun concreteSourcePathsAreNeverDepartmentContainers() {
        assertTrue(KodBoxApiClient.isConcreteSourcePath("{source:1}/"))
        assertTrue(KodBoxApiClient.isConcreteSourcePath("{source:15}/"))
        assertTrue(KodBoxApiClient.isConcreteSourcePath("{io:2}/docs/"))
        assertFalse(KodBoxApiClient.isConcreteSourcePath("{groupRootSelf}/"))
        assertFalse(KodBoxApiClient.isConcreteSourcePath("{block:enterprise}/"))

        assertFalse(KodBoxApiClient.isDepartmentContainerPath("{source:1}/"))
        assertFalse(KodBoxApiClient.isDepartmentContainerPath("{source:15}/"))
        assertFalse(KodBoxApiClient.isDepartmentContainerPath("{io:2}/docs/"))
    }

    @Test
    fun nonDepartmentVirtualPathsExcludePersonalAndSystemBuckets() {
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{userFav}/"))
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{userRecycle}/"))
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{shareToMe}/"))
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{userShare}/"))
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{block:fileType}/"))
        assertTrue(KodBoxApiClient.isNonDepartmentVirtualPath("{user:1}/"))

        assertFalse(KodBoxApiClient.isNonDepartmentVirtualPath("{source:12770}/"))
        assertFalse(KodBoxApiClient.isNonDepartmentVirtualPath("{groupRootSelf}/"))
        assertFalse(KodBoxApiClient.isNonDepartmentVirtualPath("{group:5}/"))
    }
}
