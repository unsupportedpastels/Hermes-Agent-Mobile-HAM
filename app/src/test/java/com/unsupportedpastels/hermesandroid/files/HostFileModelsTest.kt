package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HostFileModelsTest {
    @Test
    fun referenceFormatterUsesOfficialKindsAndSafeQuotePrecedence() {
        assertEquals(
            "@file:`/srv/project/my report;v1.txt`",
            formatHostFileReference(
                HostFileEntry(
                    name = "my report;v1.txt",
                    path = "/srv/project/my report;v1.txt",
                    isDirectory = false,
                    size = 4,
                    mimeType = "text/plain",
                ),
            ),
        )
        assertEquals(
            "@folder:/srv/project",
            formatHostFileReference(
                HostFileEntry("project", "/srv/project", isDirectory = true),
            ),
        )
        assertEquals(
            "@file:\"/srv/project/has`backtick.txt\"",
            formatHostFileReference(
                HostFileEntry("has`backtick.txt", "/srv/project/has`backtick.txt", false, 1),
            ),
        )
    }

    @Test
    fun referenceFormatterFailsWhenEverySafeQuoteDelimiterIsPresent() {
        val entry = HostFileEntry(
            name = "all delimiters",
            path = "/srv/a`b\"c'd",
            isDirectory = false,
            size = 1,
        )
        try {
            formatHostFileReference(entry)
            fail("Expected unsafe path to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun canonicalPathsAreAbsoluteAndRejectTraversalWithoutConstructingPaths() {
        assertEquals("/srv/project/file.txt", validCanonicalHostFilePath(" /srv/project/file.txt "))
        assertEquals("C:\\work\\file.txt", validCanonicalHostFilePath("C:\\work\\file.txt"))
        assertEquals(null, validCanonicalHostFilePath("relative/file.txt"))
        assertEquals(null, validCanonicalHostFilePath("/srv/project/../secret"))
        assertEquals(null, validCanonicalHostFilePath("/srv/project/./file"))
        assertEquals(null, validCanonicalHostFilePath("/srv/project\u0000file"))
    }
}
