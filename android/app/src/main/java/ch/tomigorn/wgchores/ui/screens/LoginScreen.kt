package ch.tomigorn.wgchores.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.ui.ErrorText
import ch.tomigorn.wgchores.ui.components.Alert
import ch.tomigorn.wgchores.ui.components.AuthPage
import ch.tomigorn.wgchores.ui.components.Brand
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.LinkButton
import ch.tomigorn.wgchores.ui.components.Note
import ch.tomigorn.wgchores.ui.components.OrDivider
import ch.tomigorn.wgchores.ui.components.PrimaryButton
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.WgcCard
import ch.tomigorn.wgchores.ui.theme.Wgc

@Composable
fun ServerBar(server: String, onChangeServer: () -> Unit) {
    // the link moves below the host when both do not fit (large fonts), so the host is never cut short
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            server.substringAfter("://"),
            style = MaterialTheme.typography.bodyMedium,
            color = Wgc.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        LinkButton(stringResource(R.string.server_change), onClick = onChangeServer, modifier = Modifier.testTag("change-server"))
    }
}

@Composable
fun LoginScreen(
    state: AuthState.Login,
    onSso: () -> Unit,
    onLogin: (username: String, pw: String) -> Unit,
    onRegister: (displayName: String, username: String, pw: String) -> Unit,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var register by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable(state.error) { mutableStateOf("") }
    var showPw by rememberSaveable { mutableStateOf(false) }
    val config = state.config
    val canRegister = config != null && config.localAccounts && config.openRegistration
    val submit = {
        if (!state.busy) {
            if (register) onRegister(displayName.trim(), username.trim(), pw) else onLogin(username.trim(), pw)
        }
    }

    AuthPage(top = { ServerBar(state.server, onChangeServer) }) {
        Brand()
        WgcCard(stringResource(if (register) R.string.login_register_title else R.string.login_title)) {
            state.error?.let { Alert(stringResource(ErrorText.of(it))) }

            if (config == null) {
                SecondaryButton(stringResource(R.string.common_retry), onClick = onRetry)
                return@WgcCard
            }

            if (config.oidc != null && !register) {
                PrimaryButton(stringResource(R.string.login_sso, config.oidc.name), onClick = onSso, modifier = Modifier.testTag("sso"))
            }
            if (config.oidc != null && config.localAccounts && !register) OrDivider()

            if (config.localAccounts) {
                if (register) {
                    Field(
                        label = stringResource(R.string.login_display_name),
                        value = displayName,
                        onValueChange = { displayName = it.take(60) },
                        hint = stringResource(R.string.login_display_name_hint),
                        modifier = Modifier.testTag("display-name"),
                    )
                }
                Field(
                    label = stringResource(R.string.login_username),
                    value = username,
                    onValueChange = { username = it.take(32) },
                    modifier = Modifier.testTag("username"),
                )
                Field(
                    label = stringResource(R.string.login_pw),
                    value = pw,
                    onValueChange = { pw = it },
                    modifier = Modifier.testTag("password"),
                    hint = if (register) stringResource(R.string.login_new_pw_hint) else null,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onIme = submit,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                painterResource(if (showPw) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                contentDescription = stringResource(if (showPw) R.string.login_hide_pw else R.string.login_show_pw),
                                tint = Wgc.colors.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                val label = stringResource(if (register) R.string.login_register_submit else R.string.login_submit)
                if (config.oidc == null || register) {
                    PrimaryButton(label, onClick = submit, busy = state.busy, modifier = Modifier.testTag("submit"))
                } else {
                    SecondaryButton(label, onClick = submit, busy = state.busy, modifier = Modifier.testTag("submit"))
                }
            }

            if (config.oidc == null && !config.localAccounts) Note(stringResource(R.string.login_nothing_enabled))
        }

        if (canRegister) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LinkButton(
                    stringResource(if (register) R.string.login_have_account else R.string.login_create_account),
                    onClick = { register = !register },
                )
            }
        }
    }
}
