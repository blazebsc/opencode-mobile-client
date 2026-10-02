package com.logicedge.opencodemobile.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun boldRendersWithoutMarkers() {
        val md = Markdown.inline("say **hello** slowly")
        assertEquals("say hello slowly", md.text)
        val spans = md.spanStyles
        assertEquals(1, spans.size)
        assertEquals(FontWeight.Bold, spans[0].item.fontWeight)
        assertEquals("hello", md.text.substring(spans[0].start, spans[0].end))
    }

    @Test
    fun emphasisAndCodeAndStrike() {
        val md = Markdown.inline("a*b* `c` ~~d~~")
        assertEquals("ab c d", md.text)
        val byStyle = md.spanStyles.map { it.item }
        assertTrue(byStyle.any { it.fontStyle == FontStyle.Italic })
        assertTrue(byStyle.any { it.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace })
        assertTrue(byStyle.any { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun linksKeepTextStyled() {
        val md = Markdown.inline("see [the docs](https://example.com/x)")
        assertEquals("see the docs", md.text)
        assertTrue(md.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun boldBeatsSingleStarPrefix() {
        val md = Markdown.inline("**r** stays")
        assertEquals(1, md.spanStyles.size)
        assertEquals(FontWeight.Bold, md.spanStyles[0].item.fontWeight)
    }

    @Test
    fun fencesSplitIntoCodeSegments() {
        val out = Markdown.segments("before\n```\nval x = 1\n```\nafter")
        assertEquals(3, out.size)
        assertTrue(out[0] is Markdown.Segment.Body)
        assertEquals("val x = 1", (out[1] as Markdown.Segment.Code).code)
        val tail = (out[2] as Markdown.Segment.Body).text
        assertTrue(tail.trim() == "after")
    }

    @Test
    fun unclosedFenceIsPlainText() {
        val out = Markdown.segments("`**not bold** nope")
        assertEquals(1, out.size)
        assertTrue(out[0] is Markdown.Segment.Body)
        assertFalse(Markdown.inline("plain").spanStyles.isNotEmpty())
    }
}
