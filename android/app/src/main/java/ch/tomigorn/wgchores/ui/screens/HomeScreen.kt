package ch.tomigorn.wgchores.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.components.AuthPage
import ch.tomigorn.wgchores.ui.components.Brand
import ch.tomigorn.wgchores.ui.components.Note
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.WgcCard
import ch.tomigorn.wgchores.ui.theme.Wgc

/** Placeholder until the chore list exists. */
@Composable
fun HomeScreen(userName: String, theme: ThemeMode, onTheme: (ThemeMode) -> Unit, onSignOut: () -> Unit) {
    AuthPage {
        Brand()
        WgcCard(stringResource(R.string.common_signed_in_as, userName)) {
            Note(stringResource(R.string.common_coming_soon))
            Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.labelMedium, color = Wgc.colors.text)
            val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { onTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size, RoundedCornerShape(8.dp)),
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Wgc.colors.accent,
                            activeContentColor = Wgc.colors.accentText,
                            inactiveContainerColor = Wgc.colors.surface,
                            inactiveContentColor = Wgc.colors.text,
                            activeBorderColor = Wgc.colors.accent,
                            inactiveBorderColor = Wgc.colors.borderStrong,
                        ),
                    ) { Text(stringResource(labels[i]), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            SecondaryButton(stringResource(R.string.nav_logout), onClick = onSignOut, modifier = Modifier.testTag("sign-out"))
        }
    }
}
