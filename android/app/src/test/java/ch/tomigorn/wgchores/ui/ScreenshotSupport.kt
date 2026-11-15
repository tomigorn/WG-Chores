package ch.tomigorn.wgchores.ui

import androidx.compose.runtime.Composable
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.ui.screens.LoginScreen
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.ui.components.BarActions
import ch.tomigorn.wgchores.ui.components.Dest
import ch.tomigorn.wgchores.ui.list.ListUi
import ch.tomigorn.wgchores.ui.screens.HouseholdScreen
import ch.tomigorn.wgchores.ui.screens.ListActions
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import org.robolectric.RuntimeEnvironment

/** Screens at the narrowest supported width (320 dp); the golden name carries font scale and theme. */
object Shots {
    const val SERVER = "https://chores.example.com"
    val config = ServerConfig(
        name = "WG Chores",
        localAccounts = true,
        openRegistration = true,
        oidc = ServerConfig.Oidc("Holy Grail"),
        minAppVersion = "1.0.0",
    )

    fun setUp(fontScale: Float, dark: Boolean, locale: String? = null) {
        val height = if (fontScale > 1f) 1500 else 800
        val lang = locale?.let { "$it-" } ?: ""
        RuntimeEnvironment.setQualifiers("${lang}w320dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
    }

    fun capture(name: String, fontScale: Float, dark: Boolean, content: @Composable () -> Unit) {
        val variant = "${if (fontScale > 1f) "font2x" else "font1x"}_${if (dark) "dark" else "light"}"
        captureRoboImage("src/test/screenshots/${name}_$variant.png") { WgcTheme(dark = dark) { content() } }
    }

    val today: LocalDate = LocalDate.parse("2026-11-14")
    private val jane = By(1, "Jane Doe")
    private val sam = By(2, "Sam")

    private fun chore(id: Long, name: String, cat: String?, iv: String, ago: Int?, by: By): Chore {
        val c = Chore(id = id, householdId = 1, name = name, category = cat, interval = iv)
        return if (ago == null) c else Chores.applyCompletion(c, today.minusDays(ago.toLong()).toString(), by, today)
    }

    val chores = listOf(
        chore(1, "Clean shower", "Bathroom", "1w", 11, sam),
        chore(2, "Clean toilet", "Bathroom", "1w", 26, jane),
        chore(3, "Restock toilet paper", "Bathroom", "2w", 1, sam),
        chore(4, "Clean fridge", "Kitchen", "1m", null, jane),
        chore(5, "Descale kettle", "Kitchen", "1m", 13, jane),
        chore(6, "Wipe stove", "Kitchen", "3d", 0, sam),
        chore(7, "Water balcony plants", "Plants", "1w", 2, jane),
        chore(8, "Water the big living room plants by the window", "Plants", "1w", 40, sam),
        chore(9, "Defrost freezer", null, "6m", 120, jane),
    )

    val list = ListUi(today, household = Household(1, "WG Zürich", 2), chores = chores, loaded = true)

    val bar = object : BarActions {
        override fun switchHousehold() {}
        override fun status(offline: Boolean) {}
        override fun navigate(d: Dest) {}
        override fun theme(mode: ThemeMode) {}
        override fun signOut() {}
    }

    val act = object : ListActions {
        override fun open(choreId: Long) {}
        override fun long(choreId: Long) {}
        override fun done(choreId: Long) {}
        override fun undo() {}
        override fun otherDate() {}
        override fun dismissSnack(seq: Long) {}
        override fun refresh() {}
        override fun retry() {}
        override fun addChore() {}
        override fun closeSheet() {}
        override fun saveSheet(doneOn: String, note: String?) {}
    }

    @Composable
    fun ListScreen(ui: ListUi) = HouseholdScreen(ui, "Jane Doe", ThemeMode.System, bar, act, inlineSheet = true)

    @Composable
    fun Login(state: AuthState.Login) =
        LoginScreen(state, onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {})
}
