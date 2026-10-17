package com.example.ui.coderdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * The row of keys a phone keyboard lacks and code needs: Tab, arrows, undo/redo, brackets, quotes and
 * operators. Shown above the on-screen keyboard while it is open.
 */
@Composable
fun CdrExtraKeys(
    onAction: (String) -> Unit,
    onType: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = listOf(
        "Tab" to "tab", "⇤" to "outdent", "↶" to "undo", "↷" to "redo",
        "←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "Home" to "home", "End" to "end",
    )
    val symbols = listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "/", "\\", "=", "+", "-", "_", "|", "&", "!", "#", "$", "%", "*", "?", ",", ".", "`", "~", "@", "^")
    Row(
        modifier.fillMaxWidth().height(40.dp).background(CarbonSurfaceVariant).border(1.dp, CarbonBorder)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { (label, action) -> Key(label, accent = true) { onAction(action) } }
        symbols.forEach { s -> Key(s, accent = false) { onType(s) } }
    }
}

@Composable
private fun Key(label: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.widthIn(min = 34.dp).height(32.dp).clip(RoundedCornerShape(6.dp))
            .background(if (accent) ElectricPurple.copy(alpha = 0.28f) else CarbonSurface)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
