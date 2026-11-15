package ch.tomigorn.wgchores.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.ui.ErrorText
import ch.tomigorn.wgchores.ui.components.AuthPage
import ch.tomigorn.wgchores.ui.components.Brand
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.Note
import ch.tomigorn.wgchores.ui.components.PrimaryButton
import ch.tomigorn.wgchores.ui.components.WgcCard

@Composable
fun ServerScreen(state: AuthState.PickServer, onConnect: (String) -> Unit) {
    var url by rememberSaveable(state.input) { mutableStateOf(state.input) }
    val connect = { if (!state.busy) onConnect(url) }
    AuthPage {
        Brand()
        WgcCard(stringResource(R.string.server_title)) {
            Field(
                label = stringResource(R.string.server_label),
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.testTag("server-url"),
                error = state.error?.let { stringResource(ErrorText.of(it)) },
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onIme = connect,
            )
            PrimaryButton(stringResource(R.string.server_connect), onClick = connect, busy = state.busy, modifier = Modifier.testTag("connect"))
            Note(stringResource(R.string.server_hint))
        }
    }
}


