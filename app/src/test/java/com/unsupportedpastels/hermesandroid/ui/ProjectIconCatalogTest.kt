package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectIconCatalogTest {
    @Test
    fun semanticProjectNamesReceiveStableDefaultIcons() {
        fun project(label: String) = ProjectSummary(
            id = ProjectId("project-${label.lowercase()}"),
            label = label,
            primaryPath = null,
            sessionCount = 0,
            previewSessions = emptyList(),
        )

        assertEquals(ProjectIconId.Home, defaultProjectIconId(project("Home")))
        assertEquals(ProjectIconId.Shield, defaultProjectIconId(project("Overwatch")))
        assertEquals(ProjectIconId.Factory, defaultProjectIconId(project("Foundry")))
        assertEquals(ProjectIconId.Terminal, defaultProjectIconId(project("HAM")))
        assertEquals(ProjectIconId.Fire, defaultProjectIconId(project("firecrawl")))
        assertEquals(ProjectIconId.Folder, defaultProjectIconId(project("Future project")))
    }

    @Test
    fun catalogHasDurableUniqueIdsAndRejectsUnknownPersistedValues() {
        val ids = ProjectIconCatalog.entries.map { it.id.persistedValue }

        assertEquals(24, ProjectIconCatalog.entries.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ProjectIconId.Terminal, ProjectIconId.fromPersistedValue("terminal"))
        assertEquals(null, ProjectIconId.fromPersistedValue("future-unknown-icon"))
    }
}
