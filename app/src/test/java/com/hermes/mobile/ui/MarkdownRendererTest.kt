package com.hermes.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test fun preservesUnderscoresInsideIdentifiers() {
        val rendered = renderInline(
            text = "STREAM_COMPLETE_OK and snake_case_value",
            baseColor = Color.White,
            codeBackground = Color.Black,
            linkColor = Color.Blue,
        )

        assertEquals("STREAM_COMPLETE_OK and snake_case_value", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun stillRendersBoundaryUnderscoresAsItalic() {
        val rendered = renderInline(
            text = "Use _italic words_ here",
            baseColor = Color.White,
            codeBackground = Color.Black,
            linkColor = Color.Blue,
        )

        assertEquals("Use italic words here", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
}
