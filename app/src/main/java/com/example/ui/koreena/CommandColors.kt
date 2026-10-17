package com.example.ui.koreena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.koreena.CommandReader
import com.example.data.koreena.Kind
import com.example.data.koreena.Reading
import com.example.ui.theme.CarbonBorder

/**
 * The one palette that colours commands everywhere in the app (Miss Koreena's reader, her chat answers, the
 * cheat sheet). ALL CAPS names are amber on a soft amber chip so they stand out in a GRUB file; red is kept
 * for danger only, so nothing else is red.
 */
object CommandColors {
    private val Amber = Color(0xFFFFB300)
    private val Red = Color(0xFFFF5252)

    val PlainText = Color(0xFFE2E8F0)
    val CodeBackground = Color(0xFF0A0B10)

    fun style(kind: Kind): SpanStyle = when (kind) {
        Kind.CAPS -> SpanStyle(color = Amber, fontWeight = FontWeight.Bold, background = Amber.copy(alpha = 0.16f))
        Kind.COMMAND -> SpanStyle(color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
        Kind.KEYWORD -> SpanStyle(color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
        Kind.PRIVILEGE -> SpanStyle(color = Color(0xFFFF6EB4), fontWeight = FontWeight.Bold)
        Kind.DANGER -> SpanStyle(color = Red, fontWeight = FontWeight.Bold, background = Red.copy(alpha = 0.22f), textDecoration = TextDecoration.Underline)
        Kind.FLAG -> SpanStyle(color = Color(0xFF7EE787))
        Kind.KEY -> SpanStyle(color = Color(0xFF8AB4F8))
        Kind.PATH -> SpanStyle(color = Color(0xFF2DD4BF))
        Kind.DEVICE -> SpanStyle(color = Color(0xFFFF8A3D), fontWeight = FontWeight.SemiBold)
        Kind.STRING -> SpanStyle(color = Color(0xFFE0C080), fontStyle = FontStyle.Italic)
        Kind.VARIABLE -> SpanStyle(color = Color(0xFFD58BF0))
        Kind.NUMBER -> SpanStyle(color = Color(0xFFD4E157))
        Kind.UUID -> SpanStyle(color = Color(0xFF90A4AE), fontStyle = FontStyle.Italic)
        Kind.OPERATOR -> SpanStyle(color = Color(0xFFF1F5F9), fontWeight = FontWeight.Bold)
        Kind.COMMENT -> SpanStyle(color = Color(0xFF64748B), fontStyle = FontStyle.Italic)
        Kind.PLAIN, Kind.SPACE -> SpanStyle(color = PlainText)
    }

    fun color(kind: Kind): Color = style(kind).color

    /** The order of the colour key on screen. */
    val legendOrder = listOf(
        Kind.CAPS, Kind.COMMAND, Kind.PRIVILEGE, Kind.KEYWORD, Kind.FLAG, Kind.KEY, Kind.PATH, Kind.DEVICE,
        Kind.UUID, Kind.STRING, Kind.VARIABLE, Kind.NUMBER, Kind.OPERATOR, Kind.COMMENT, Kind.DANGER,
    )
}

fun Reading.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    tokens.forEach { t ->
        if (t.kind == Kind.SPACE) append(t.text)
        else withStyle(CommandColors.style(t.kind)) { append(t.text) }
    }
}

/** A command or GRUB snippet drawn in the shared colours. Selectable, so it can be copied. */
@Composable
fun CommandText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    reading: Reading = remember(text) { CommandReader.read(text) },
) {
    val annotated = remember(reading) { reading.toAnnotatedString() }
    Box(
        modifier
            .background(CommandColors.CodeBackground, RoundedCornerShape(8.dp))
            .border(1.dp, CarbonBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        SelectionContainer {
            Text(annotated, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.45f, color = CommandColors.PlainText)
        }
    }
}
