package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.ui.theme.Wgc

data class SnackAction(val label: String, val tag: String, val run: () -> Unit)

/** Inverted bar at the bottom: optional bold chore name, a short text, actions (same look as the web app). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneSnackbar(strong: String?, text: String, actions: List<SnackAction>, modifier: Modifier = Modifier) {
    val dark = Wgc.colors.dark
    val bg = if (dark) Color(0xFFECECEF) else Color(0xFF1F2023)
    val fg = if (dark) Color(0xFF18181B) else Color(0xFFF3F3F5)
    val act = if (dark) Color(0xFF2A55C5) else Color(0xFFA9BEFF)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.BottomCenter) {
        FlowRow(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .background(bg, RoundedCornerShape(12.dp))
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("snackbar"),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val style = MaterialTheme.typography.bodyMedium
            if (strong != null) {
                // name and "– done today" wrap as two units at large text, never inside the date words
                FlowRow(
                    Modifier.padding(vertical = 8.dp).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                        Text(strong, style = style, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(text, style = style, color = fg, maxLines = 1, softWrap = false)
                }
            } else {
                Text(text, style = style, color = fg, modifier = Modifier.padding(vertical = 8.dp).padding(end = 8.dp))
            }
            Row(Modifier.weight(1f).align(Alignment.CenterVertically), horizontalArrangement = Arrangement.End) {
                for (a in actions) {
                    TextButton(onClick = a.run, modifier = Modifier.heightIn(min = 48.dp).testTag(a.tag), shape = RoundedCornerShape(8.dp)) {
                        Text(a.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = act, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
