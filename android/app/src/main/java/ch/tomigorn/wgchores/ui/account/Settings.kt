package ch.tomigorn.wgchores.ui.account

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.tomigorn.wgchores.BuildConfig
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.notify.OsNotify
import ch.tomigorn.wgchores.ui.chore.Pill
import ch.tomigorn.wgchores.ui.chore.account
import ch.tomigorn.wgchores.ui.components.LinkButton
import ch.tomigorn.wgchores.ui.components.PageBar
import ch.tomigorn.wgchores.ui.components.RowsCard
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.SectionTitle
import ch.tomigorn.wgchores.ui.components.ToastHost
import ch.tomigorn.wgchores.ui.list.Notice
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** "system", "en" or "de". */
data class SettingsUi(
    val language: String = "system",
    val notifyDone: Boolean = false,
    val notifyDue: Boolean = false,
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val server: String? = null,
    val appVersion: String = "",
    val serverVersion: String? = null,
    val snack: Notice? = null,
    val snackSeq: Long = 0,
    val os: OsNotify = OsNotify.Allowed,
)

interface SettingsActions {
    fun back()
    fun theme(mode: ThemeMode)
    fun language(tag: String)
    fun notify(done: Boolean, on: Boolean)
    fun open(url: String)
    fun reload()
    fun dismissSnack(seq: Long)
    /** Ask for the permission, or open the system settings when only they can turn notifications on. */
    fun allowNotifications() {}
}

object AppLanguage {
    fun current(): String = AppCompatDelegate.getApplicationLocales().get(0)?.language?.takeIf { it == "en" || it == "de" } ?: "system"

    /** Per-app language; AppCompat stores it on Android 12 and older, the system does from 13 on. */
    fun set(tag: String) = AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val wgc = app as WgcApp
    val ui = MutableStateFlow(SettingsUi(language = AppLanguage.current(), appVersion = BuildConfig.VERSION_NAME))
    @Volatile private var lastError: Exception? = null
    private val sync = NotifySync { done, on ->
        try {
            val a = wgc.account() ?: throw OfflineException()
            val me = a.api.patchMe(buildJsonObject { put(if (done) "notify_done" else "notify_due", on) })
            wgc.container.notifyStore.saveFlags(a.key, me.notifyDone, me.notifyDue)
            NotifySync.State(me.notifyDone, me.notifyDue)
        } catch (e: Exception) {
            lastError = e
            throw e
        }
    }

    init {
        viewModelScope.launch { sync.state.collect { s -> ui.update { it.copy(notifyDone = s.done, notifyDue = s.due) } } }
        load()
    }

    /** Reads the switches from /me; on failure they stay off with a Try again. */
    fun load() {
        ui.update { it.copy(loadFailed = false) }
        viewModelScope.launch {
            val session = wgc.container.session.read()
            ui.update { it.copy(server = session.server, serverVersion = session.config?.version?.takeIf { v -> v.isNotBlank() }) }
            try {
                val a = wgc.account() ?: throw OfflineException()
                val me = a.api.me()
                wgc.container.notifyStore.saveFlags(a.key, me.notifyDone, me.notifyDue)
                sync.confirmed(me.notifyDone, me.notifyDue)
                ui.update { it.copy(loaded = true, loadFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(loadFailed = !it.loaded) }
            }
        }
    }

    fun language(tag: String) {
        ui.update { it.copy(language = tag) }
        AppLanguage.set(tag)
        wgc.container.languageChanged(tag)
    }

    /** Saved on the server (PATCH /me), one change at a time; the switches also start or stop the background check. */
    fun notify(done: Boolean, on: Boolean) {
        viewModelScope.launch {
            if (!sync.set(done, on)) {
                val text = if (lastError is OfflineException) R.string.done_connect else R.string.done_failed
                ui.update { it.copy(snack = Notice.Res(text), snackSeq = it.snackSeq + 1) }
            }
        }
    }

    fun dismiss(seq: Long) = ui.update { if (it.snackSeq == seq) it.copy(snack = null) else it }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: SettingsUi, theme: ThemeMode, act: SettingsActions) {
    Box(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PageBar(stringResource(R.string.settings_title), act::back)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_theme), Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
                    ThemeMode.entries.forEachIndexed { i, m -> Pill(stringResource(labels[i]), theme == m, "theme-${m.name.lowercase()}") { act.theme(m) } }
                }
                SectionTitle(stringResource(R.string.settings_language), Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to R.string.settings_lang_system, "en" to R.string.settings_lang_en, "de" to R.string.settings_lang_de).forEach { (tag, label) ->
                        Pill(stringResource(label), ui.language == tag, "lang-$tag") { act.language(tag) }
                    }
                }
                SectionTitle(stringResource(R.string.settings_notifications), Modifier.padding(top = 8.dp))
                RowsCard {
                    val shown = ui.os == OsNotify.Allowed
                    Toggle(stringResource(R.string.settings_notify_done), stringResource(R.string.settings_notify_done_hint), ui.notifyDone && shown, ui.loaded, "notify-done") { act.notify(true, it) }
                    HorizontalDivider(color = Wgc.colors.border)
                    Toggle(stringResource(R.string.settings_notify_due), stringResource(R.string.settings_notify_due_hint), ui.notifyDue && shown, ui.loaded, "notify-due") { act.notify(false, it) }
                }
                if (ui.os == OsNotify.Ask || ui.os == OsNotify.Rationale || ui.os == OsNotify.Blocked) {
                    val text = when (ui.os) {
                        OsNotify.Rationale -> R.string.notify_os_rationale
                        OsNotify.Blocked -> R.string.notify_os_blocked
                        else -> R.string.notify_os_ask
                    }
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.text, modifier = Modifier.padding(start = 4.dp).testTag("notify-os-text"))
                    val button = if (ui.os == OsNotify.Blocked) R.string.notify_open_settings else R.string.notify_allow
                    SecondaryButton(stringResource(button), act::allowNotifications, Modifier.testTag("notify-allow"))
                }
                if (ui.loadFailed) {
                    Text(stringResource(R.string.settings_load_failed), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.dangerText, modifier = Modifier.padding(start = 4.dp))
                    SecondaryButton(stringResource(R.string.common_retry), act::reload, Modifier.testTag("notify-retry"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                    val line = with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
                    Box(Modifier.size(line), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(line * 0.8f))
                    }
                    Text(stringResource(R.string.settings_notify_app), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
                }
                ui.server?.let { s ->
                    HorizontalDivider(color = Wgc.colors.border, modifier = Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkButton(stringResource(R.string.legal_privacy), { act.open("$s/privacy") }, Modifier.testTag("legal-privacy"))
                        LinkButton(stringResource(R.string.legal_deletion), { act.open("$s/account-deletion") }, Modifier.testTag("legal-deletion"))
                    }
                }
                if (ui.appVersion.isNotBlank()) VersionLine(ui.appVersion, ui.serverVersion)
            }
        }
        ToastHost(ui.snack?.let { noticeText(it) }, ui.snackSeq, act::dismissSnack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Toggle(label: String, hint: String, on: Boolean, enabled: Boolean, tag: String, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = set).testTag(tag).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Wgc.colors.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted)
        }
        Switch(
            checked = on, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Wgc.colors.accent, checkedThumbColor = Wgc.colors.accentText, uncheckedBorderColor = Wgc.colors.borderStrong, uncheckedThumbColor = Wgc.colors.borderStrong, uncheckedTrackColor = Wgc.colors.surface),
        )
    }
}

/** One muted line "App 1.0.0 · Server 1.0.0"; when it does not fit, Server goes on a second line. Never cut. */
@Composable
private fun VersionLine(app: String, server: String?) {
    val style = MaterialTheme.typography.bodySmall
    val appText = stringResource(R.string.settings_version_app, app)
    val serverText = server?.let { stringResource(R.string.settings_version_server, it) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
        val both = serverText?.let { "$appText · $it" }
        val fits = both != null && measurer.measure(both, style, maxLines = 1, softWrap = false).size.width <= with(density) { maxWidth.roundToPx() }
        Column(Modifier.testTag("version-line")) {
            val lines = if (both == null || serverText == null) listOf(appText) else if (fits) listOf(both) else listOf(appText, serverText)
            lines.forEach { Text(it, style = style, color = Wgc.colors.muted, maxLines = 1, softWrap = false, modifier = Modifier.testTag("version-text")) }
        }
    }
}
