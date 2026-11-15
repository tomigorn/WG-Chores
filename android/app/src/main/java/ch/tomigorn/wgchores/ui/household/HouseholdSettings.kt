package ch.tomigorn.wgchores.ui.household

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Expiry
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.domain.Invite
import ch.tomigorn.wgchores.domain.Member
import ch.tomigorn.wgchores.domain.NewInvite
import ch.tomigorn.wgchores.domain.Rules
import ch.tomigorn.wgchores.ui.ErrorText
import ch.tomigorn.wgchores.ui.chore.Phase
import ch.tomigorn.wgchores.ui.chore.account
import ch.tomigorn.wgchores.ui.components.Alert
import ch.tomigorn.wgchores.ui.components.Avatar
import ch.tomigorn.wgchores.ui.components.ConfirmDialog
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.ItemRow
import ch.tomigorn.wgchores.ui.components.ButtonPair
import ch.tomigorn.wgchores.ui.components.PadCard
import ch.tomigorn.wgchores.ui.components.PageBar
import ch.tomigorn.wgchores.ui.components.RowsCard
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.SectionTitle
import ch.tomigorn.wgchores.ui.components.ToastHost
import ch.tomigorn.wgchores.ui.list.Notice
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.screens.StateMessage
import ch.tomigorn.wgchores.ui.theme.Wgc
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HhUi(
    val meId: Long = 0,
    val phase: Phase = Phase.Loading,
    val offline: Boolean = false,
    val household: Household? = null,
    val members: List<Member> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val archived: List<Chore> = emptyList(),
    val name: String = "",
    val directory: Boolean = false,
    val who: String = "",
    val hits: List<Member> = emptyList(),
    val searched: String = "",
    val addError: Int? = null,
    val newInvite: NewInvite? = null,
    val busy: Boolean = false,
    val removing: Member? = null,
    val leaving: Boolean = false,
    val lastMember: Boolean = false,
    val now: Instant = Instant.now(),
    val snack: Notice? = null,
    val snackSeq: Long = 0,
    /** Left (or deleted) the household: go to the household list. */
    val left: Boolean = false,
)

interface HhActions {
    fun back()
    fun retry()
    fun name(v: String)
    fun rename()
    fun who(v: String)
    fun addByName()
    fun add(userId: Long)
    fun askRemove(m: Member)
    fun remove()
    fun closeRemove()
    fun createInvite()
    fun share(url: String)
    fun copy(url: String)
    fun cancelInvite(i: Invite)
    fun restore(c: Chore)
    fun askLeave()
    fun leave()
    fun closeLeave()
    fun dismissSnack(seq: Long)
}

/** Household settings: rename, members (add by username or directory search, remove), invite links, archived chores, leave. */
class HouseholdSettingsViewModel(app: Application, private val hid: Long) : AndroidViewModel(app) {
    private val wgc = app as WgcApp
    val ui = MutableStateFlow(HhUi())
    private var searchJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            val a = wgc.account() ?: return@launch
            val on = Rules.memberDirectory { a.api.searchUsers(it) }
            ui.update { it.copy(directory = on) }
        }
    }

    private suspend fun acct(): Account = wgc.account() ?: throw OfflineException()

    private fun toast(n: Notice) = ui.update { it.copy(snack = n, snackSeq = it.snackSeq + 1) }

    fun load() {
        viewModelScope.launch {
            try {
                val a = acct()
                val (h, rest) = coroutineScope {
                    val h = async { a.api.household(hid) }
                    val m = async { a.api.members(hid) }
                    val i = async { a.api.invites(hid) }
                    val c = async { a.api.archivedChores(hid) }
                    h.await() to Triple(m.await(), i.await(), c.await())
                }
                ui.update {
                    it.copy(
                        meId = a.me.id, household = h, name = if (it.phase == Phase.Ready) it.name else h.name,
                        members = rest.first, invites = rest.second, archived = rest.third, now = Instant.now(), offline = false, phase = Phase.Ready,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                ui.update { it.copy(phase = if (e.status == 404 || e.status == 403) Phase.Gone else if (it.phase == Phase.Loading) Phase.Failed else it.phase) }
            } catch (e: Exception) {
                ui.update { it.copy(offline = e is OfflineException, phase = if (it.phase == Phase.Loading) Phase.Failed else it.phase) }
            }
        }
    }

    /** Runs a change; failures become a short text ("Connect to the internet to save this" offline). */
    private fun run(done: Notice? = null, block: suspend (Account) -> Unit) {
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(acct())
                done?.let(::toast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(Notice.Res(ErrorText.of(e, R.string.done_connect)))
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    val actions = object : HhActions {
        override fun back() {}
        override fun retry() = load()
        override fun name(v: String) = ui.update { it.copy(name = v.take(60)) }

        override fun rename() {
            val n = ui.value.name.trim()
            if (n.isEmpty() || n == ui.value.household?.name) return
            run(Notice.Res(R.string.hh_renamed)) { a ->
                val h = a.api.renameHousehold(hid, n)
                ui.update { it.copy(household = h) }
            }
        }

        override fun who(v: String) {
            ui.update { it.copy(who = v.take(60), addError = null) }
            if (!ui.value.directory) return
            searchJob?.cancel()
            val q = v.trim()
            if (q.length < 2) {
                ui.update { it.copy(hits = emptyList(), searched = "") }
                return
            }
            searchJob = viewModelScope.launch {
                delay(200)
                try {
                    val r = acct().api.searchUsers(q)
                    ui.update { it.copy(hits = r, searched = q) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui.update { it.copy(hits = emptyList()) }
                }
            }
        }

        private fun add(username: String?, userId: Long?) {
            ui.update { it.copy(busy = true, addError = null) }
            viewModelScope.launch {
                try {
                    val m = acct().api.addMember(hid, username, userId)
                    ui.update { it.copy(who = "", hits = emptyList(), searched = "") }
                    toast(Notice.Res(R.string.hh_added, m.displayName))
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui.update { it.copy(addError = ErrorText.of(e, R.string.done_connect)) }
                } finally {
                    ui.update { it.copy(busy = false) }
                }
            }
        }

        override fun addByName() {
            val u = ui.value.who.trim()
            if (u.isNotEmpty()) add(u, null)
        }

        override fun add(userId: Long) = add(null, userId)
        override fun askRemove(m: Member) = ui.update { it.copy(removing = m) }
        override fun closeRemove() = ui.update { it.copy(removing = null) }

        override fun remove() {
            val m = ui.value.removing ?: return
            ui.update { it.copy(removing = null) }
            run(Notice.Res(R.string.hh_removed, m.displayName)) { a ->
                a.api.removeMember(hid, m.id)
                load()
            }
        }

        override fun createInvite() = run { a ->
            val n = a.api.createInvite(hid)
            val list = a.api.invites(hid)
            ui.update { it.copy(newInvite = n, invites = list, now = Instant.now()) }
        }

        override fun share(url: String) {}
        override fun copy(url: String) {}

        override fun cancelInvite(i: Invite) = run(Notice.Res(R.string.hh_invite_cancelled)) { a ->
            a.api.cancelInvite(hid, i.id)
            ui.update { s -> s.copy(invites = s.invites.filter { it.id != i.id }, newInvite = s.newInvite?.takeIf { it.id != i.id }) }
        }

        override fun restore(c: Chore) = run(Notice.Res(R.string.hh_restored, c.name)) { a ->
            a.api.restoreChore(c.id)
            ui.update { s -> s.copy(archived = s.archived.filter { it.id != c.id }) }
        }

        override fun askLeave() = ui.update { it.copy(leaving = true, lastMember = it.members.size <= 1) }
        override fun closeLeave() = ui.update { it.copy(leaving = false) }

        override fun leave() {
            ui.update { it.copy(busy = true) }
            viewModelScope.launch {
                try {
                    acct().api.leaveHousehold(hid, ui.value.lastMember)
                    ui.update { it.copy(leaving = false, left = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.code == "last_member_confirm") ui.update { it.copy(lastMember = true) }
                    else {
                        ui.update { it.copy(leaving = false) }
                        toast(Notice.Res(ErrorText.of(e, R.string.done_connect)))
                    }
                } catch (e: Exception) {
                    ui.update { it.copy(leaving = false) }
                    toast(Notice.Res(ErrorText.of(e, R.string.done_connect)))
                } finally {
                    ui.update { it.copy(busy = false) }
                }
            }
        }

        override fun dismissSnack(seq: Long) = ui.update { if (it.snackSeq == seq) it.copy(snack = null) else it }
    }

    /** Copied on Android 12 and older: newer versions show their own confirmation. */
    fun copied() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(Notice.Res(R.string.hh_copied))
    }

    companion object {
        fun factory(app: Application, id: Long): ViewModelProvider.Factory = viewModelFactory { initializer { HouseholdSettingsViewModel(app, id) } }
    }
}

fun shareInvite(context: Context, url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
    }
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
}

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun expiryText(e: Expiry): String = when (e) {
    Expiry.Expired -> stringResource(R.string.expiry_expired)
    Expiry.Soon -> stringResource(R.string.expiry_soon)
    is Expiry.Hours -> pluralStringResource(R.plurals.expiry_hours, e.n, e.n)
    is Expiry.Days -> pluralStringResource(R.plurals.expiry_days, e.n, e.n)
}

@Composable
fun HouseholdSettingsScreen(ui: HhUi, act: HhActions) {
    Box(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PageBar(stringResource(R.string.hh_title), act::back)
            when (ui.phase) {
                Phase.Loading -> Text(stringResource(R.string.common_loading), color = Wgc.colors.muted, modifier = Modifier.padding(16.dp))
                Phase.Gone -> StateMessage(R.drawable.ic_circle_alert, null, stringResource(R.string.error_not_found), stringResource(R.string.nav_households), act::back)
                Phase.Failed -> StateMessage(
                    if (ui.offline) R.drawable.ic_wifi_off else R.drawable.ic_circle_alert,
                    null,
                    stringResource(if (ui.offline) R.string.error_offline else R.string.error_generic),
                    stringResource(R.string.common_retry),
                    act::retry,
                )
                Phase.Ready -> Content(ui, act)
            }
        }
        ToastHost(ui.snack?.let { noticeText(it) }, ui.snackSeq, act::dismissSnack, Modifier.align(Alignment.BottomCenter))
    }
    ui.removing?.let { m ->
        ConfirmDialog(
            stringResource(R.string.hh_remove_title, m.displayName), stringResource(R.string.hh_remove_text),
            stringResource(R.string.hh_remove), stringResource(R.string.hh_keep), act::remove, act::closeRemove,
        )
    }
    if (ui.leaving) {
        val name = ui.household?.name.orEmpty()
        ConfirmDialog(
            stringResource(if (ui.lastMember) R.string.hh_leave_last_title else R.string.hh_leave_title, name),
            stringResource(if (ui.lastMember) R.string.hh_leave_last_text else R.string.hh_leave_text),
            stringResource(if (ui.lastMember) R.string.hh_delete_confirm else R.string.hh_leave_confirm),
            stringResource(R.string.hh_keep), act::leave, act::closeLeave, enabled = !ui.busy,
        )
    }
}

@Composable
private fun Content(ui: HhUi, act: HhActions) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PadCard {
            Field(stringResource(R.string.hh_name), ui.name, act::name, Modifier.testTag("hh-name"), imeAction = ImeAction.Done, onIme = act::rename)
            val n = ui.name.trim()
            SecondaryButton(stringResource(R.string.hh_save), act::rename, Modifier.testTag("hh-rename"), enabled = !ui.busy && n.isNotEmpty() && n != ui.household?.name)
        }

        SectionTitle(stringResource(R.string.hh_members), Modifier.padding(top = 12.dp))
        RowsCard {
            ui.members.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                PersonRow(m, you = m.id == ui.meId) {
                    if (m.id != ui.meId) SmallButton(stringResource(R.string.hh_remove), stringResource(R.string.hh_remove_label, m.displayName), "remove-${m.id}") { act.askRemove(m) }
                }
            }
        }
        PadCard {
            Field(
                stringResource(R.string.hh_add), ui.who, act::who, Modifier.testTag("add-member"),
                hint = stringResource(if (ui.directory) R.string.hh_add_search else R.string.hh_add_hint_exact),
                imeAction = ImeAction.Done, onIme = { if (!ui.directory) act.addByName() },
            )
            if (!ui.directory) SecondaryButton(stringResource(R.string.hh_add_button), act::addByName, Modifier.testTag("add-member-submit"), enabled = !ui.busy && ui.who.isNotBlank(), icon = R.drawable.ic_user_plus)
            ui.addError?.let { Alert(stringResource(it)) }
            if (ui.directory && ui.who.trim().length >= 2) {
                if (ui.hits.isNotEmpty()) {
                    Column {
                        ui.hits.forEachIndexed { i, h ->
                            if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                            PersonRow(h, you = false, size = 32) {
                                if (ui.members.any { it.id == h.id }) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Wgc.colors.accent, modifier = Modifier.size(20.dp))
                                } else {
                                    SmallButton(stringResource(R.string.hh_add_button), stringResource(R.string.hh_add_button) + " " + h.displayName, "hit-${h.id}") { act.add(h.id) }
                                }
                            }
                        }
                    }
                } else if (ui.searched == ui.who.trim()) {
                    Text(stringResource(R.string.hh_no_hits), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
                }
            }
        }

        SectionTitle(stringResource(R.string.hh_invites), Modifier.padding(top = 12.dp))
        PadCard {
            Text(stringResource(R.string.hh_invite_hint), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
            ui.newInvite?.let { n ->
                Text(n.url, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.text, modifier = Modifier.testTag("invite-url"))
                ButtonPair(
                    { m -> SecondaryButton(stringResource(R.string.hh_copy), { act.copy(n.url) }, m.testTag("invite-copy"), icon = R.drawable.ic_copy) },
                    { m -> SecondaryButton(stringResource(R.string.hh_share), { act.share(n.url) }, m.testTag("invite-share"), icon = R.drawable.ic_share_2) },
                )
            }
            SecondaryButton(stringResource(R.string.hh_invite_create), act::createInvite, Modifier.testTag("invite-create"), enabled = !ui.busy, icon = R.drawable.ic_link)
        }
        if (ui.invites.isNotEmpty()) {
            RowsCard {
                ui.invites.forEachIndexed { i, inv ->
                    if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                    LineRow(
                        icon = R.drawable.ic_link,
                        title = stringResource(R.string.hh_invite_by, inv.createdBy.displayName),
                        sub = expiryText(Rules.expiresIn(inv.expiresAt, ui.now)),
                    ) {
                        SmallButton(stringResource(R.string.hh_invite_cancel), stringResource(R.string.hh_invite_cancel_label, inv.createdBy.displayName), "invite-cancel-${inv.id}") { act.cancelInvite(inv) }
                    }
                }
            }
        } else {
            Text(stringResource(R.string.hh_no_invites), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp))
        }

        SectionTitle(stringResource(R.string.hh_archived), Modifier.padding(top = 12.dp))
        if (ui.archived.isNotEmpty()) {
            RowsCard {
                ui.archived.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                    LineRow(icon = null, title = c.name, sub = c.category) {
                        SmallButton(stringResource(R.string.chore_restore), stringResource(R.string.chore_restore) + ": " + c.name, "restore-${c.id}") { act.restore(c) }
                    }
                }
            }
        } else {
            Text(stringResource(R.string.hh_no_archived), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp))
        }

        HorizontalDivider(color = Wgc.colors.border, modifier = Modifier.padding(top = 12.dp))
        SecondaryButton(stringResource(R.string.hh_leave), act::askLeave, Modifier.testTag("hh-leave"), icon = R.drawable.ic_log_out, danger = true)
    }
}

@Composable
fun SmallButton(text: String, label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp).testTag(tag).semantics { contentDescription = label },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Wgc.colors.borderStrong),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Wgc.colors.surface, contentColor = Wgc.colors.text),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
}

@Composable
private fun PersonRow(m: Member, you: Boolean, size: Int = 36, trailing: @Composable () -> Unit) {
    ItemRow(leading = { Avatar(m.displayName, m.pictureUrl, size) }, trailing = trailing) {
        Text(
            m.displayName + if (you) " (" + stringResource(R.string.hh_you) + ")" else "",
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        m.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun LineRow(icon: Int?, title: String, sub: String?, trailing: @Composable () -> Unit) {
    ItemRow(leading = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.padding(top = 3.dp).size(18.dp)) } }, trailing = trailing) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
