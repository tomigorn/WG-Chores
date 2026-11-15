package ch.tomigorn.wgchores.ui.account

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.api.User
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.domain.Days
import ch.tomigorn.wgchores.domain.Device
import ch.tomigorn.wgchores.domain.PasswordProblem
import ch.tomigorn.wgchores.domain.Rules
import ch.tomigorn.wgchores.ui.ErrorText
import ch.tomigorn.wgchores.ui.chore.Phase
import ch.tomigorn.wgchores.ui.chore.account
import ch.tomigorn.wgchores.ui.components.Alert
import ch.tomigorn.wgchores.ui.components.Avatar
import ch.tomigorn.wgchores.ui.components.ConfirmDialog
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.ItemRow
import ch.tomigorn.wgchores.ui.components.PadCard
import ch.tomigorn.wgchores.ui.components.PageBar
import ch.tomigorn.wgchores.ui.components.RowsCard
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.SectionTitle
import ch.tomigorn.wgchores.ui.components.ToastHost
import ch.tomigorn.wgchores.ui.household.SmallButton
import ch.tomigorn.wgchores.ui.list.Notice
import ch.tomigorn.wgchores.ui.list.dayText
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.screens.StateMessage
import ch.tomigorn.wgchores.ui.theme.Wgc
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AccountUi(
    val phase: Phase = Phase.Loading,
    val offline: Boolean = false,
    val me: User? = null,
    val config: ServerConfig? = null,
    val tz: String = "UTC",
    val name: String = "",
    val current: String = "",
    val pw: String = "",
    val repeat: String = "",
    val nameError: Int? = null,
    val pwError: Int? = null,
    val devices: List<Device> = emptyList(),
    val busy: Boolean = false,
    val deleting: Boolean = false,
    val typed: String = "",
    val deleteError: Int? = null,
    val snack: Notice? = null,
    val snackSeq: Long = 0,
) {
    val sso: Boolean get() = me?.kind == "sso"
    val manageUrl: String? get() = me?.accountUrl ?: config?.oidc?.accountUrl
    val canDelete: Boolean get() = me != null && Rules.canConfirmDelete(me.kind, me.displayName, typed)
}

interface AccountActions {
    fun back()
    fun retry()
    fun manage(url: String)
    fun name(v: String)
    fun saveName()
    fun current(v: String)
    fun pw(v: String)
    fun repeat(v: String)
    fun savePassword()
    fun signOutDevice(d: Device)
    fun signOut()
    fun askDelete()
    fun typed(v: String)
    fun delete()
    fun closeDelete()
    fun dismissSnack(seq: Long)
}

/** Profile, SSO link out or local name and password, signed-in devices, sign out, delete account. */
class AccountViewModel(app: Application) : AndroidViewModel(app) {
    private val wgc = app as WgcApp
    private val c = wgc.container
    val ui = MutableStateFlow(AccountUi())

    init {
        load()
    }

    private fun toast(n: Notice) = ui.update { it.copy(snack = n, snackSeq = it.snackSeq + 1) }

    fun load() {
        viewModelScope.launch {
            val s = c.session.read()
            ui.update { it.copy(config = s.config, tz = s.config?.tz ?: "UTC") }
            try {
                val a = wgc.account() ?: throw OfflineException()
                val me = a.api.me()
                val devices = runCatching { a.api.devices() }.getOrDefault(ui.value.devices)
                ui.update { it.copy(phase = Phase.Ready, offline = false, me = me, name = if (it.phase == Phase.Ready) it.name else me.displayName, devices = devices) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(offline = e is OfflineException, phase = if (it.phase == Phase.Loading) Phase.Failed else it.phase) }
            }
        }
    }

    fun saveName() {
        val n = ui.value.name.trim()
        if (n.isEmpty() || n == ui.value.me?.displayName || ui.value.busy) return
        ui.update { it.copy(busy = true, nameError = null) }
        viewModelScope.launch {
            try {
                val me = (wgc.account() ?: throw OfflineException()).api.patchMe(buildJsonObject { put("display_name", n) })
                ui.update { it.copy(me = me) }
                c.auth.refreshUser()
                toast(Notice.Res(R.string.account_name_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(nameError = ErrorText.of(e, R.string.done_connect)) }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    fun savePassword() {
        val s = ui.value
        if (s.busy) return
        val p = Rules.passwordProblem(s.pw, s.repeat)
        ui.update { it.copy(pwError = p?.let { pr -> if (pr == PasswordProblem.Short) R.string.error_password_too_short else R.string.account_mismatch }) }
        if (p != null) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                (wgc.account() ?: throw OfflineException()).api.patchMe(buildJsonObject {
                    put("current_password", s.current)
                    put("new_password", s.pw)
                })
                ui.update { it.copy(current = "", pw = "", repeat = "") }
                toast(Notice.Res(R.string.account_pw_saved))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(pwError = ErrorText.of(e, R.string.done_connect)) }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    fun signOutOther(d: Device, label: String) {
        viewModelScope.launch {
            try {
                (wgc.account() ?: throw OfflineException()).api.deleteDevice(d.id)
                ui.update { s -> s.copy(devices = s.devices.filter { it.id != d.id }) }
                toast(Notice.Res(R.string.devices_signed_out, label))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(Notice.Res(ErrorText.of(e, R.string.done_connect)))
                load()
            }
        }
    }

    /** Deleted on the server: this device forgets the account, its waiting marks and the server. */
    fun delete() {
        val s = ui.value
        val me = s.me ?: return
        if (!s.canDelete || s.busy) return
        ui.update { it.copy(busy = true, deleteError = null) }
        viewModelScope.launch {
            try {
                val a = wgc.account() ?: throw OfflineException()
                if (me.kind == "sso") a.api.deleteMe(confirmUsername = s.typed.trim()) else a.api.deleteMe(currentPassword = s.typed) // leak-check: ok
                runCatching { c.sync.forget(a) }
                c.auth.accountDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, deleteError = ErrorText.of(e, R.string.done_connect)) }
            }
        }
    }

    fun edit(f: (AccountUi) -> AccountUi) = ui.update(f)
}

@Composable
fun deviceName(d: Device): String = d.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(if (d.kind == "web") R.string.devices_browser else R.string.devices_android)

@Composable
fun AccountScreen(ui: AccountUi, act: AccountActions) {
    Box(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PageBar(stringResource(R.string.account_title), act::back)
            val me = ui.me
            when {
                ui.phase == Phase.Failed -> StateMessage(
                    if (ui.offline) R.drawable.ic_wifi_off else R.drawable.ic_circle_alert,
                    null,
                    stringResource(if (ui.offline) R.string.error_offline else R.string.error_generic),
                    stringResource(R.string.common_retry),
                    act::retry,
                )
                me == null -> Text(stringResource(R.string.common_loading), color = Wgc.colors.muted, modifier = Modifier.padding(16.dp))
                else -> Content(ui, me, act)
            }
        }
        ToastHost(ui.snack?.let { noticeText(it) }, ui.snackSeq, act::dismissSnack, Modifier.align(Alignment.BottomCenter))
    }
    val me = ui.me
    if (ui.deleting && me != null) {
        ConfirmDialog(
            title = stringResource(R.string.account_delete_title),
            text = stringResource(R.string.account_delete_text),
            confirm = stringResource(R.string.account_delete_button),
            cancel = stringResource(R.string.account_keep),
            onConfirm = act::delete,
            onDismiss = act::closeDelete,
            enabled = ui.canDelete && !ui.busy,
        ) {
            if (ui.sso) {
                Field(stringResource(R.string.account_delete_type_sso, me.displayName), ui.typed, act::typed, Modifier.testTag("delete-confirm"), imeAction = ImeAction.Done)
            } else {
                Field(
                    stringResource(R.string.account_delete_password), ui.typed, act::typed, Modifier.testTag("delete-confirm"),
                    keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation(), imeAction = ImeAction.Done,
                )
            }
            ui.deleteError?.let { Alert(stringResource(it)) }
        }
    }
}

@Composable
private fun Content(ui: AccountUi, me: User, act: AccountActions) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PadCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(me.displayName, me.pictureUrl, 64)
                Column(Modifier.weight(1f)) {
                    Text(me.displayName, style = MaterialTheme.typography.titleLarge, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    me.username?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (ui.sso) {
            ui.config?.oidc?.let { Text(stringResource(R.string.account_managed_by, it.name), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp)) }
            ui.manageUrl?.let { url -> SecondaryButton(stringResource(R.string.account_manage), { act.manage(url) }, Modifier.testTag("account-manage"), icon = R.drawable.ic_external_link) }
        } else {
            PadCard {
                ui.nameError?.let { Alert(stringResource(it)) }
                Field(stringResource(R.string.account_name), ui.name, act::name, Modifier.testTag("account-name"), imeAction = ImeAction.Done, onIme = act::saveName)
                val n = ui.name.trim()
                SecondaryButton(stringResource(R.string.account_name_save), act::saveName, Modifier.testTag("account-name-save"), enabled = !ui.busy && n.isNotEmpty() && n != me.displayName)
            }
            SectionTitle(stringResource(R.string.account_change_pw), Modifier.padding(top = 8.dp))
            PadCard {
                ui.pwError?.let { Alert(stringResource(it)) }
                val hidden = PasswordVisualTransformation()
                Field(stringResource(R.string.account_current), ui.current, act::current, Modifier.testTag("pw-current"), keyboardType = KeyboardType.Password, visualTransformation = hidden)
                Field(stringResource(R.string.account_new), ui.pw, act::pw, Modifier.testTag("pw-new"), hint = stringResource(R.string.login_new_pw_hint), keyboardType = KeyboardType.Password, visualTransformation = hidden)
                Field(stringResource(R.string.account_repeat), ui.repeat, act::repeat, Modifier.testTag("pw-repeat"), keyboardType = KeyboardType.Password, visualTransformation = hidden, imeAction = ImeAction.Done, onIme = act::savePassword)
                SecondaryButton(stringResource(R.string.account_pw_save), act::savePassword, Modifier.testTag("pw-save"), enabled = !ui.busy && ui.current.isNotEmpty() && ui.pw.isNotEmpty() && ui.repeat.isNotEmpty())
            }
        }

        if (ui.devices.isNotEmpty()) {
            SectionTitle(stringResource(R.string.devices_title), Modifier.padding(top = 8.dp))
            RowsCard {
                ui.devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                    DeviceRow(d, ui.tz) { act.signOutDevice(d) }
                }
            }
        }

        SecondaryButton(stringResource(R.string.nav_logout), act::signOut, Modifier.padding(top = 8.dp).testTag("account-sign-out"), icon = R.drawable.ic_log_out)

        HorizontalDivider(color = Wgc.colors.border, modifier = Modifier.padding(top = 12.dp))
        SectionTitle(stringResource(R.string.account_delete))
        Text(stringResource(R.string.account_delete_text), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp))
        SecondaryButton(stringResource(R.string.account_delete), act::askDelete, Modifier.testTag("account-delete"), icon = R.drawable.ic_trash_2, danger = true)
    }
}

@Composable
private fun DeviceRow(d: Device, tz: String, onSignOut: () -> Unit) {
    val name = deviceName(d)
    ItemRow(
        Modifier.testTag("device-${d.id}"),
        leading = { Icon(painterResource(if (d.kind == "web") R.drawable.ic_compass else R.drawable.ic_smartphone), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.padding(top = 3.dp).size(18.dp)) },
        trailing = { SmallButton(stringResource(R.string.devices_sign_out), stringResource(R.string.devices_sign_out_label, name), "device-sign-out-${d.id}", onSignOut) },
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (d.current) FontWeight.SemiBold else FontWeight.Normal, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = if (d.current) stringResource(R.string.devices_this) else lastSeen(d.lastSeenAt, tz)?.let { stringResource(R.string.devices_last_seen, it) }
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (d.current) Wgc.colors.accent else Wgc.colors.muted, maxLines = 2) }
    }
}

@Composable
private fun lastSeen(at: String, tz: String): String? {
    val day = runCatching { OffsetDateTime.parse(at).atZoneSameInstant(Days.zone(tz)).toLocalDate() }.getOrNull() ?: return null
    return dayText(day.toString(), Days.today(tz))
}
