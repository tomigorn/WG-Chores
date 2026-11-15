package ch.tomigorn.wgchores.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.ui.components.AuthPage
import ch.tomigorn.wgchores.ui.components.Brand
import ch.tomigorn.wgchores.ui.components.Note
import ch.tomigorn.wgchores.ui.components.PrimaryButton
import ch.tomigorn.wgchores.ui.components.WgcCard

@Composable
fun UpdateScreen(state: AuthState.UpdateRequired, onOpenStore: () -> Unit, onChangeServer: () -> Unit) {
    AuthPage(top = { ServerBar(state.server, onChangeServer) }) {
        Brand()
        WgcCard(stringResource(R.string.update_title)) {
            Note(stringResource(R.string.update_text, state.minVersion, state.appVersion))
            PrimaryButton(stringResource(R.string.update_open_store), onClick = onOpenStore)
        }
    }
}
