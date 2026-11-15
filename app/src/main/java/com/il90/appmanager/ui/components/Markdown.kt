package com.il90.appmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The blocks release notes are made of. Everything else is shown as a paragraph. */
sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

/**
 * Turns GitHub release notes into simple blocks: headings, bullets and paragraphs.
 * Tables become one line per row, HTML comments and separator rows disappear.
 */
fun parseMarkdown(source: String): List<MdBlock> {
    val text = source.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").replace("\r", "")
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    var inCode = false
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            flush()
            inCode = !inCode
            continue
        }
        if (inCode) {
            if (trimmed.isNotEmpty()) blocks += MdBlock.Paragraph("`$trimmed`")
            continue
        }
        when {
            trimmed.isEmpty() -> flush()
            trimmed.matches(Regex("^[-=*_]{3,}$")) -> flush()
            trimmed.startsWith("#") -> {
                flush()
                blocks += MdBlock.Heading(trimmed.trimStart('#').trim())
            }
            trimmed.startsWith("|") -> {
                flush()
                val cells = trimmed.trim('|').split("|").map { it.trim() }
                val separator = cells.all { it.matches(Regex("^:?-{2,}:?$")) || it.isEmpty() }
                val row = cells.filter { it.isNotEmpty() }.joinToString("  ·  ")
                if (!separator && row.isNotEmpty()) blocks += MdBlock.Bullet(row, 0)
            }
            Regex("^([-*+•]|\\d+[.)])\\s+").containsMatchIn(trimmed) -> {
                flush()
                val depth = ((line.length - line.trimStart().length) / 2).coerceIn(0, 2)
                blocks += MdBlock.Bullet(trimmed.replaceFirst(Regex("^([-*+•]|\\d+[.)])\\s+"), ""), depth)
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks += MdBlock.Paragraph(trimmed.trimStart('>').trim())
            }
            else -> {
                // A wrapped bullet continues on the next, indented line.
                val last = blocks.lastOrNull()
                if (paragraph.isEmpty() && last is MdBlock.Bullet && raw.startsWith("  ")) {
                    blocks[blocks.lastIndex] = last.copy(text = last.text + " " + trimmed)
                } else {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
    }
    flush()
    return blocks
}

private val inlinePattern = Regex(
    "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(https?://[^\\s)>\\]]+)|(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])"
)

fun inlineMarkdown(text: String, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val links = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var index = 0
    for (match in inlinePattern.findAll(text)) {
        append(text.substring(index, match.range.first))
        val (bold, bold2, code, label, url, bare, italic) = match.destructured
        when {
            bold.isNotEmpty() || bold2.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold.ifEmpty { bold2 }) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
            url.isNotEmpty() -> withLink(LinkAnnotation.Url(url, links)) { append(label) }
            bare.isNotEmpty() -> {
                val clean = bare.trimEnd('.', ',', ';', ':')
                withLink(LinkAnnotation.Url(clean, links)) { append(clean.removePrefix("https://").removePrefix("http://")) }
                append(bare.substring(clean.length))
            }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}

@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier, maxBlocks: Int = Int.MAX_VALUE) {
    val blocks = remember(source) { parseMarkdown(source) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val body = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.take(maxBlocks).forEachIndexed { i, block ->
            when (block) {
                is MdBlock.Heading -> {
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    Text(
                        inlineMarkdown(block.text, linkColor, codeBackground),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.depth * 14).dp)) {
                    Text("•", style = body, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(inlineMarkdown(block.text, linkColor, codeBackground), style = body)
                }
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text, linkColor, codeBackground), style = body)
            }
        }
    }
}
