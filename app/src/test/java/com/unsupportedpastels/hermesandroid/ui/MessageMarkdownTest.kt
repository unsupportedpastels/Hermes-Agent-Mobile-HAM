package com.unsupportedpastels.hermesandroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMarkdownTest {
    @Test
    fun parsesListsInlineFormattingAndFencedCodeWithoutLiteralMarkers() {
        val blocks = parseMessageMarkdown(
            """
            Summary

            - **Container:** removed; no `service` remains.
            - **Image:** still present:
            ```text
            example/image:latest
            Size: 800 MB
            ```
            """.trimIndent(),
        )

        assertEquals(4, blocks.size)
        assertEquals("Summary", (blocks[0] as MarkdownTextBlock).plainText)

        val firstBullet = blocks[1] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Bullet, firstBullet.kind)
        assertEquals("•", firstBullet.prefix)
        assertEquals("Container: removed; no service remains.", firstBullet.plainText)
        assertTrue(firstBullet.inlines.single { it.text == "Container:" }.bold)
        assertTrue(firstBullet.inlines.single { it.text == "service" }.code)

        val secondBullet = blocks[2] as MarkdownTextBlock
        assertEquals("Image: still present:", secondBullet.plainText)
        assertTrue(secondBullet.inlines.single { it.text == "Image:" }.bold)

        val code = blocks[3] as MarkdownCodeBlock
        assertEquals("text", code.language)
        assertEquals("example/image:latest\nSize: 800 MB", code.code)
        assertFalse(code.code.contains("```"))
    }

    @Test
    fun parsesHeadingsQuotesNumberedItemsLinksAndTextStyles() {
        val blocks = parseMessageMarkdown(
            """
            ## Result
            > Read the warning first.
            1. Open **Settings**.
            2. Select *Network*, visit [documentation](https://example.invalid), and ignore ~~old~~ advice.
            """.trimIndent(),
        )

        val heading = blocks[0] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Heading, heading.kind)
        assertEquals(2, heading.headingLevel)
        assertEquals("Result", heading.plainText)

        val quote = blocks[1] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Quote, quote.kind)
        assertEquals("Read the warning first.", quote.plainText)

        val firstItem = blocks[2] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Numbered, firstItem.kind)
        assertEquals("1.", firstItem.prefix)
        assertTrue(firstItem.inlines.single { it.text == "Settings" }.bold)

        val secondItem = blocks[3] as MarkdownTextBlock
        assertTrue(secondItem.inlines.single { it.text == "Network" }.italic)
        assertEquals("https://example.invalid", secondItem.inlines.single { it.text == "documentation" }.link)
        assertTrue(secondItem.inlines.single { it.text == "old" }.strikethrough)
    }

    @Test
    fun preservesUnmatchedInlineMarkersAndTreatsStreamingFenceAsCode() {
        val unmatched = parseMessageMarkdown("Keep **unfinished and `partial")
            .single() as MarkdownTextBlock
        assertEquals("Keep **unfinished and `partial", unmatched.plainText)

        val streaming = parseMessageMarkdown("```kotlin\nval answer = 42")
            .single() as MarkdownCodeBlock
        assertEquals("kotlin", streaming.language)
        assertEquals("val answer = 42", streaming.code)
    }

    @Test
    fun normalizesBlankParagraphsAndEmptyInput() {
        assertTrue(parseMessageMarkdown("").isEmpty())
        val blocks = parseMessageMarkdown("First\nline\n\n\nSecond")
        assertEquals(2, blocks.size)
        assertEquals("First\nline", (blocks[0] as MarkdownTextBlock).plainText)
        assertEquals("Second", (blocks[1] as MarkdownTextBlock).plainText)
        assertNull((blocks[1] as MarkdownTextBlock).prefix)
    }
}
