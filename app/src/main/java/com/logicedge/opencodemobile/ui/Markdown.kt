package com.logicedge.opencodemobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

object Markdown {

    sealed interface Segment {
        data class Body(val text: String) : Segment
        data class Code(val code: String) : Segment
    }

    private val codeFence = Regex("""```[^\n]*\n?([\s\S]*?)```""")

    fun segments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var index = 0
        for (match in codeFence.findAll(text)) {
            if (match.range.first > index) out += Segment.Body(text.substring(index, match.range.first))
            out += Segment.Code(match.groupValues[1].trim('\n'))
            index = match.range.last + 1
        }
        if (index < text.length) out += Segment.Body(text.substring(index))
        return out
    }

    // 1=code, 2/3=link text+url, 4/5=bold, 6/7=italic, 8=strikethrough
    private val inlineRule = Regex(
        """`([^`\n]+)`|\[([^\]]+)]\(([^)\s]+)\)|(\*\*|__)(.+?)\4|(\*|_)([^*_\n]+?)\6|~~(.+?)~~""",
    )

    fun inline(
        text: String,
        codeBackground: Color = Color(0x22808080),
        linkColor: Color = Color(0xFF4C9AFF),
    ): AnnotatedString = buildAnnotatedString {
        var index = 0
        for (match in inlineRule.findAll(text)) {
            if (match.range.first > index) append(text.substring(index, match.range.first))
            val groups = match.groups
            when {
                groups[1] != null -> styled(groups[1]!!.value, SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground))
                groups[2] != null -> styled(groups[2]!!.value, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                groups[4] != null -> styled(groups[5]!!.value, SpanStyle(fontWeight = FontWeight.Bold))
                groups[6] != null -> styled(groups[7]!!.value, SpanStyle(fontStyle = FontStyle.Italic))
                groups[8] != null -> styled(groups[8]!!.value, SpanStyle(textDecoration = TextDecoration.LineThrough))
            }
            index = match.range.last + 1
        }
        if (index < text.length) append(text.substring(index))
    }

    private fun AnnotatedString.Builder.styled(text: String, style: SpanStyle) {
        pushStyle(style)
        append(text)
        pop()
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Markdown.segments(text).forEach { segment ->
            when (segment) {
                is Markdown.Segment.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        segment.code,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                is Markdown.Segment.Body -> if (segment.text.isNotBlank()) {
                    Text(Markdown.inline(segment.text), style = style, color = color)
                }
            }
        }
    }
}
