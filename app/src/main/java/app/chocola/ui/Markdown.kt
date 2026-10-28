package app.chocola.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun blockSpacing(block: MarkdownBlock, previous: MarkdownBlock?, first: Boolean): androidx.compose.ui.unit.Dp = when {
    first -> 18.dp
    block.meta == "continuation" -> 0.dp
    previous == null -> 0.dp
    block.type == MarkdownType.HEADING -> 18.dp
    previous.type == MarkdownType.HEADING -> 8.dp
    block.type in listOf(MarkdownType.BULLET, MarkdownType.NUMBER) && previous.type in listOf(MarkdownType.BULLET, MarkdownType.NUMBER) -> 6.dp
    block.type in listOf(MarkdownType.CODE, MarkdownType.TABLE, MarkdownType.MATH) || previous.type in listOf(MarkdownType.CODE, MarkdownType.TABLE, MarkdownType.MATH) -> 14.dp
    else -> 12.dp
}

@Composable internal fun MarkdownPiece(prepared: PreparedBlock, animate: Boolean = false, animateInitial: Boolean = animate) {
    val block = prepared.block
    when (block.type) {
        MarkdownType.CODE -> CodeBlock(block.text, block.meta, streaming = animate, animateInitial = animateInitial)
        MarkdownType.MATH -> MathBlock(requireNotNull(block.math), prepared.streaming, animate && animateInitial)
        MarkdownType.RULE -> Hairline(Modifier.padding(vertical = 8.dp))
        MarkdownType.TABLE -> MarkdownTableView(prepared, animate, animateInitial)
        MarkdownType.BULLET, MarkdownType.NUMBER -> Row(Modifier.padding(start = (block.indent.coerceAtMost(4) * 12).dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (block.type == MarkdownType.NUMBER) Txt("${block.meta}.", Modifier.alignByBaseline().widthIn(min = 21.dp), size = 16, lineHeight = 27, color = LocalTranscriptInk.current)
            else Box(Modifier.width(18.dp).height(27.dp), contentAlignment = Alignment.CenterStart) { Box(Modifier.size(4.dp).background(LocalTranscriptInk.current, CircleShape)) }
            RichText(prepared.content, Modifier.weight(1f).then(if (block.type == MarkdownType.NUMBER) Modifier.alignByBaseline() else Modifier), animate = animate, animateInitial = animateInitial, streaming = prepared.streaming)
        }
        MarkdownType.QUOTE -> Row(Modifier.drawBehind { drawRoundRect(Color(0xFFCDCED2), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.width(2.dp))
            RichText(prepared.content, Modifier.weight(1f), color = LocalTranscriptSecondary.current, animate = animate, animateInitial = animateInitial, streaming = prepared.streaming)
        }
        MarkdownType.HEADING -> RichText(prepared.content, size = 18, lineHeight = 28, weight = FontWeight.Medium, animate = animate, animateInitial = animateInitial, streaming = prepared.streaming)
        MarkdownType.TEXT -> RichText(prepared.content, animate = animate, animateInitial = animateInitial, streaming = prepared.streaming)
    }
}

@Composable private fun RichText(content: AnnotatedString, modifier: Modifier = Modifier, size: Int = 16, lineHeight: Int = 27,
    color: Color = Color.Unspecified, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start, animate: Boolean = false, animateInitial: Boolean = animate, streaming: Boolean = false) {
    val scale = LocalAppFontScale.current
    MathRichText(content, modifier, TextStyle(color = if (color == Color.Unspecified) LocalTranscriptInk.current else color, fontSize = (size * scale).sp, lineHeight = (lineHeight * scale).sp,
        fontFamily = FontFamily.SansSerif, fontWeight = weight, textAlign = align, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineBreak = LineBreak.Simple, hyphens = Hyphens.None,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), streaming, animate, animateInitial)
}

@Composable fun Markdown(text: String, modifier: Modifier = Modifier, streaming: Boolean = false, animateInitial: Boolean = false) {
    val parser = remember { StreamingMarkdown() }
    val prepared by produceState<List<PreparedBlock>>(emptyList(), text, streaming) {
        value = withContext(Dispatchers.Default) { parser.update(text, streaming).map { RichDocuments.prepare(it, streaming) } }
    }
    Column(modifier.fillMaxWidth()) {
        prepared.forEachIndexed { index, part -> key(index) {
            Box(Modifier.fillMaxWidth().padding(top = blockSpacing(part.block, prepared.getOrNull(index - 1)?.block, false))) {
                MarkdownPiece(part, streaming || animateInitial)
            }
        } }
    }
}

@Composable private fun MarkdownTableView(prepared: PreparedBlock, animate: Boolean, animateInitial: Boolean) {
    val table = requireNotNull(prepared.block.table)
    val count = table.rows.maxOfOrNull { it.size } ?: return
    if (count == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth / count).coerceIn(140.dp, 230.dp)
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).horizontalFades(scroll, Color(0xFFF8F8F9))) {
            Column(Modifier.horizontalScroll(scroll).width(cellWidth * count)) {
                prepared.cells.forEachIndexed { row, cells ->
                    Row(Modifier.fillMaxWidth().background(if (row == 0) Color(0xFFF0F0F2) else Color(0xFFF8F8F9))) {
                        cells.forEachIndexed { column, content ->
                            RichText(content, Modifier.width(cellWidth).padding(horizontal = 13.dp, vertical = 10.dp), size = 14, lineHeight = 23,
                                weight = if (row == 0) FontWeight.Medium else FontWeight.Normal, color = Palette.Ink,
                                align = when (table.alignments.getOrNull(column)) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start }, animate = animate, animateInitial = animateInitial, streaming = prepared.streaming)
                        }
                    }
                    if (row < prepared.cells.lastIndex) Hairline()
                }
            }
        }
    }
}

@Composable fun CodeBlock(code: String, language: String = "", maxLines: Int = Int.MAX_VALUE, streaming: Boolean = false, animateInitial: Boolean = streaming) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF5F5F6))) {
        Row(Modifier.fillMaxWidth().height(43.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(language.ifBlank { "代码" }, Modifier.weight(1f), size = 12, color = Palette.Secondary, mono = true)
            CopyControl(code, "复制完整代码")
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().horizontalFades(scroll, Color(0xFFF5F5F6))) {
            SelectionContainer {
                RenderText(AnnotatedString(code), Modifier.horizontalScroll(scroll).padding(start = 14.dp, end = 14.dp, bottom = 14.dp), codeStyle(), streaming, maxLines, animateInitial)
            }
        }
    }
}

@Composable private fun codeStyle() = TextStyle(color = Palette.Ink, fontSize = (13 * LocalAppFontScale.current).sp,
    lineHeight = (22 * LocalAppFontScale.current).sp, fontFamily = FontFamily.Monospace, platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable internal fun VirtualCodePiece(piece: CodePiece, scroll: ScrollState, animate: Boolean, animateInitial: Boolean = animate) {
    val width = maxOf(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 44.dp, (piece.columns * 13 * .63f * LocalAppFontScale.current + 28).dp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = if (piece.chunk == 0) 14.dp else 0.dp, topEnd = if (piece.chunk == 0) 14.dp else 0.dp,
        bottomStart = if (piece.last) 14.dp else 0.dp, bottomEnd = if (piece.last) 14.dp else 0.dp)).background(Color(0xFFF5F5F6))) {
        if (piece.chunk == 0) Row(Modifier.fillMaxWidth().height(43.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(piece.language.ifBlank { "代码" }, Modifier.weight(1f), size = 12, color = Palette.Secondary, mono = true)
            CopyControl(piece.full, "复制完整代码")
        }
        Box(Modifier.fillMaxWidth().horizontalFades(scroll, Color(0xFFF5F5F6))) {
            SelectionContainer {
                RenderText(AnnotatedString(piece.code), Modifier.horizontalScroll(scroll).width(width).padding(start = 14.dp, end = 14.dp, bottom = if (piece.last) 14.dp else 0.dp), codeStyle(), animate, animateInitial = animateInitial)
            }
        }
    }
}
