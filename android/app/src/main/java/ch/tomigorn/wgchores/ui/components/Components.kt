package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.ui.theme.Wgc

private val Tap = 48.dp

/** Page frame of the signed-out screens: centred column, max 400 dp, scrolls, clear of bars and keyboard. */
@Composable
fun AuthPage(top: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Wgc.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = if (top == null) 48.dp else 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            top?.invoke()
            content()
        }
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .background(Wgc.colors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_list_checks), contentDescription = null, tint = Wgc.colors.accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = Wgc.colors.text,
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = Wgc.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun WgcCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Wgc.colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Wgc.colors.text, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
fun Alert(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Wgc.colors.dangerBg, RoundedCornerShape(8.dp))
            .border(1.dp, Wgc.colors.dangerBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val line = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
        Box(Modifier.height(line), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_circle_alert),
                contentDescription = null,
                tint = Wgc.colors.dangerText,
                modifier = Modifier.size(line * 0.9f),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.dangerText)
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Wgc.colors.muted,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: Int? = null) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = Tap),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Wgc.colors.accent,
            contentColor = Wgc.colors.accentText,
            disabledContainerColor = Wgc.colors.accent.copy(alpha = 0.6f),
            disabledContentColor = Wgc.colors.accentText,
        ),
    ) { ButtonLabel(text, busy, Wgc.colors.accentText, icon) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: Int? = null,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = Tap),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Wgc.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Wgc.colors.surface, contentColor = if (danger) Wgc.colors.dangerText else Wgc.colors.text),
    ) { ButtonLabel(text, busy, if (danger) Wgc.colors.dangerText else Wgc.colors.text, icon) }
}

@Composable
private fun ButtonLabel(text: String, busy: Boolean, color: androidx.compose.ui.graphics.Color, icon: Int? = null) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    } else if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = Tap), shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Wgc.colors.accent, textAlign = TextAlign.Center)
    }
}

@Composable
fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = Wgc.colors.border)
        Text(stringResource(R.string.login_or), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
        HorizontalDivider(Modifier.weight(1f), color = Wgc.colors.border)
    }
}

/** Label above the field (as on the web), so long labels and large fonts never overlap the input. */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    onIme: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Wgc.colors.text, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(8.dp),
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Wgc.colors.surface,
                unfocusedContainerColor = Wgc.colors.surface,
                errorContainerColor = Wgc.colors.surface,
                unfocusedBorderColor = Wgc.colors.borderStrong,
                focusedBorderColor = Wgc.colors.accent,
                errorBorderColor = Wgc.colors.dangerText,
            ),
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.dangerText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted)
        }
    }
}
