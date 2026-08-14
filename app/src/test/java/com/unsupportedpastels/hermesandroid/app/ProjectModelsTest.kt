package com.unsupportedpastels.hermesandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun projectIdentityIsNotDerivedFromDisplayBasename() {
        val first = ProjectSummary(
            id = ProjectId("project:/srv/one/app"),
            label = "app",
            primaryPath = "/srv/one/app",
            sessionCount = 1,
            previewSessions = listOf(SessionSummary(DurableSessionId("one"), "One")),
        )
        val second = first.copy(
            id = ProjectId("project:/srv/two/app"),
            primaryPath = "/srv/two/app",
        )

        assertTrue(first.id != second.id)
        assertEquals("app", first.label)
        assertEquals("app", second.label)
    }

    @Test
    fun projectPreviewAndLabelsAreBoundedAtTheModelBoundary() {
        val project = ProjectSummary(
            id = ProjectId("p1"),
            label = "x".repeat(500),
            primaryPath = "/workspace/app",
            sessionCount = 99,
            previewSessions = (1..20).map {
                SessionSummary(DurableSessionId("s$it"), "title $it")
            },
        )

        assertTrue(project.label.length <= ProjectSummary.MAX_LABEL_LENGTH)
        assertTrue(project.previewSessions.size <= ProjectSummary.MAX_PREVIEW_SESSIONS)
    }

    @Test
    fun projectWorkspacePathRejectsBlankRelativeAndControlCharacters() {
        assertNull(validProjectWorkspacePath(null))
        assertNull(validProjectWorkspacePath("  "))
        assertNull(validProjectWorkspacePath("relative/path"))
        assertNull(validProjectWorkspacePath("/tmp\nbad"))
        assertEquals("/srv/app", validProjectWorkspacePath("  /srv/app  "))
        assertEquals("C:\\work\\app", validProjectWorkspacePath("C:\\work\\app"))
    }

    @Test
    fun hostFolderNameRejectsTraversalSeparatorsAndControlCharacters() {
        assertEquals("New Project", validHostFolderName("  New Project  "))
        assertNull(validHostFolderName(""))
        assertNull(validHostFolderName("."))
        assertNull(validHostFolderName(".."))
        assertNull(validHostFolderName("nested/folder"))
        assertNull(validHostFolderName("nested\\folder"))
        assertNull(validHostFolderName("bad\u0000name"))
        assertNull(validHostFolderName("bad\nname"))
    }

    @Test
    fun projectSessionLoadStateRepresentsLoadedEmptySeparatelyFromLoading() {
        val loadedEmpty = ProjectSessionLoadState.Loaded(emptyList())

        assertEquals(ProjectSessionLoadState.Loaded(emptyList()), loadedEmpty)
        assertTrue(loadedEmpty != ProjectSessionLoadState.Loading)
    }
}
