package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.api.User
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Category
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.domain.Device
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.domain.Invite
import ch.tomigorn.wgchores.domain.InviteBy
import ch.tomigorn.wgchores.domain.InvitePreview
import ch.tomigorn.wgchores.domain.Member
import ch.tomigorn.wgchores.domain.NewInvite
import ch.tomigorn.wgchores.ui.account.AccountScreen
import ch.tomigorn.wgchores.ui.account.AccountUi
import ch.tomigorn.wgchores.ui.account.SettingsScreen
import ch.tomigorn.wgchores.ui.account.SettingsUi
import ch.tomigorn.wgchores.ui.chore.ChoreEditScreen
import ch.tomigorn.wgchores.ui.chore.ChoreScreen
import ch.tomigorn.wgchores.ui.chore.ChoreUi
import ch.tomigorn.wgchores.ui.chore.EditUi
import ch.tomigorn.wgchores.ui.chore.Entry
import ch.tomigorn.wgchores.ui.chore.EntrySheet
import ch.tomigorn.wgchores.ui.chore.Phase
import ch.tomigorn.wgchores.ui.household.HhUi
import ch.tomigorn.wgchores.ui.household.HouseholdSettingsScreen
import ch.tomigorn.wgchores.ui.screens.HouseholdsScreen
import ch.tomigorn.wgchores.ui.screens.HouseholdsUi
import ch.tomigorn.wgchores.ui.screens.JoinUi
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actions that do nothing, for screens drawn from a fixed state. */
inline fun <reified T : Any> noop(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

object Screens {
    private val sam = By(2, "Sam")
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    val toilet: Chore = Chores.applyCompletion(Chore(2, 1, "Clean toilet", "Bathroom", "1w"), "2026-10-19", sam, Shots.today).copy(usualInterval = 9)

    val chore = ChoreUi(
        Shots.today,
        chore = toilet,
        entries = listOf(
            Entry("a", "2026-11-14", null, By(1, "Jane Doe"), waiting = true),
            Entry("b", "2026-10-19", null, sam, false),
            Entry("c", "2026-10-12", "used the new cleaner", By(3, "Alexandra Example-Longname"), false),
            Entry("d", "2026-10-03", null, By(1, "Jane Doe"), false),
            Entry("e", "2025-09-24", null, By(4, "Max", former = true), false),
        ),
        next = "2025-09-24,e",
        phase = Phase.Ready,
    )

    val chorePageSheet = chore.copy(sheet = EntrySheet.Edit(chore.entries[2]))

    val edit = EditUi(
        editing = true, phase = Phase.Ready, chore = toilet, householdId = 1,
        categories = listOf(Category("Bathroom", 3), Category("Kitchen", 3), Category("Plants", 2)),
        name = "Clean toilet", category = "Bath", interval = "1w",
    )

    val households = HouseholdsUi(
        listOf(Household(1, "WG Zürich", 2), Household(2, "Family", 3)), loaded = true,
        join = JoinUi(link = "https://chores.example.com/invite/abc", token = "abc", preview = InvitePreview("Lake House", "Sam", "2026-11-20T13:00:00Z", valid = true), now = now),
    )

    val household = HhUi(
        meId = 1, phase = Phase.Ready, household = Household(1, "WG Zürich", 3), name = "WG Zürich",
        members = listOf(Member(1, "Jane Doe", "anna"), Member(2, "Sam", "sam"), Member(3, "Alexandra Example-Longname", "alexandra.example")),
        invites = listOf(Invite(1, InviteBy(2, "Sam"), "2026-11-19T13:00:00Z"), Invite(2, InviteBy(1, "Jane Doe"), "2026-11-14T15:30:00Z")),
        archived = listOf(Chore(9, 1, "Defrost freezer", "Kitchen", "6m", archived = true)),
        newInvite = NewInvite(3, "https://chores.example.com/invite/Xy7_kP2qLm9", "2026-11-21T12:00:00Z"),
        now = now,
    )

    val account = AccountUi(
        phase = Phase.Ready,
        me = User(1, "anna", "Jane Doe", "local"),
        config = Shots.config,
        tz = "Europe/Zurich",
        name = "Jane Doe",
        devices = listOf(
            Device("d1", "android", "Pixel 9", "2026-11-14T10:00:00Z", current = true),
            Device("d2", "web", null, "2026-11-13T10:00:00Z"),
            Device("d3", "android", "Samsung Galaxy Tab S9 FE", "2026-10-01T10:00:00Z"),
        ),
    )

    val accountSso = account.copy(me = User(1, null, "Jane Doe", "sso", accountUrl = "https://sso.example.com/if/user/"), devices = account.devices.take(1))

    val settings = SettingsUi(language = "system", notifyDone = true, notifyDue = false, loaded = true, server = Shots.SERVER, appVersion = "1.0.0", serverVersion = "1.0.0")
}

/** The screens of P5 at 320 dp: font 1.0 and 2.0, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotScreensTest(private val fontScale: Float, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font{0}_dark{1}")
        fun params() = listOf(arrayOf<Any>(1f, false), arrayOf<Any>(1f, true), arrayOf<Any>(2f, false), arrayOf<Any>(2f, true))
    }

    @Before fun setUp() = Shots.setUp(fontScale, dark)

    private fun tall() = Shots.setUp(fontScale, dark, tall = true)

    @Test fun chorePage() = Shots.capture("chore", fontScale, dark) { ChoreScreen(Screens.chore, noop()) }

    @Test fun chorePageEntrySheet() = Shots.capture("chore_entry", fontScale, dark) { ChoreScreen(Screens.chorePageSheet, noop(), inlineSheet = true) }

    @Test fun choreEdit() = Shots.capture("chore_edit", fontScale, dark) { ChoreEditScreen(Screens.edit, noop()) }

    @Test fun households() = Shots.capture("households_join", fontScale, dark) {
        HouseholdsScreen(Screens.households, "Jane Doe", 0, ThemeMode.System, false, Shots.bar, {}, {}, {}, join = noop())
    }

    @Test fun householdSettings() = tall().let { Shots.capture("household_settings", fontScale, dark) { HouseholdSettingsScreen(Screens.household, noop()) } }

    @Test fun account() = tall().let { Shots.capture("account", fontScale, dark) { AccountScreen(Screens.account, noop()) } }

    @Test fun accountSso() = Shots.capture("account_sso", fontScale, dark) { AccountScreen(Screens.accountSso, noop()) }

    @Test fun settings() = Shots.capture("settings", fontScale, dark) { SettingsScreen(Screens.settings, ThemeMode.System, noop()) }
}
