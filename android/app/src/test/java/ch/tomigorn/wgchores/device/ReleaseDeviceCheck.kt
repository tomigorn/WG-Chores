package ch.tomigorn.wgchores.device

import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.domain.Member
import ch.tomigorn.wgchores.testing.FakeServer
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** R8 builds on the emulator (task releaseDeviceCheck): release refuses local http, minified build runs against the fake server and takes the store screenshots. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseDeviceCheck {
    private val pkg = "ch.tomigorn.wgchores"
    private val out = File(prop("wgc.device.out")).apply { mkdirs() }
    private val adb = Adb()
    private val fake = FakeServer()
    private val reviewer = By(1, "Reviewer")

    private fun prop(name: String) = System.getProperty(name).orEmpty()

    @Before fun setUp() {
        assumeTrue("only in the releaseDeviceCheck task", prop("wgc.device.out").isNotEmpty())
        demoStatusBar()
        seedDemo()
        fake.start()
    }

    @After fun tearDown() {
        fake.close()
        adb.run("uninstall", pkg)
    }

    @Test fun a_releaseApkStartsAndRefusesLocalHttp() {
        val signed = File(out, "release-debugkey.apk")
        val apksigner = File(System.getenv("ANDROID_HOME")).resolve("build-tools").listFiles()!!.maxBy { it.name }.resolve("apksigner")
        exec(
            apksigner.path, "sign", "--ks", prop("wgc.device.debugKeystore"), "--ks-pass", "pass:android", // leak-check: ok
            "--ks-key-alias", "androiddebugkey", "--out", signed.path, prop("wgc.device.releaseApk"),
        )
        install(signed)
        launch()
        connect()
        adb.waitFor("http refused") { n -> n.any { it.text == "Only https:// addresses are allowed." } }
        adb.screenshot(File(out, "release-http-refused.png"))
        assertTrue("the release app sent nothing to the fake server", fake.requests.isEmpty())
    }

    @Test fun b_minifiedAppRunsAgainstTheServerAndTakesStoreScreenshots() {
        val shots = File(out, "screenshots").apply { mkdirs() }
        install(File(prop("wgc.device.minifiedApk")))
        launch()
        connect()
        adb.waitFor("sign-in form") { n -> n.count { it.cls == "android.widget.EditText" } >= 2 }
        val fields = adb.dump().filter { it.cls == "android.widget.EditText" }
        adb.tap(fields[0])
        adb.shell("input text anna")
        adb.tap(fields[1])
        adb.shell("input text '${fake.pw.replace(" ", "%s")}'")
        adb.shell("input keyevent KEYCODE_ENTER")
        adb.waitFor("chore list") { n -> n.any { it.text == "Wipe counters" } && n.any { it.text == "Demo WG" || it.desc.startsWith("Demo WG") } }
        hideKeyboard()
        settle()
        adb.screenshot(File(shots, "1-chore-list.png"))

        adb.tap(adb.waitFor("done button") { n -> n.any { it.desc == "Mark Dust the shelves done" } }.first { it.desc == "Mark Dust the shelves done" })
        adb.waitFor("snackbar") { n -> n.any { it.text == "Undo" } }
        adb.screenshot(File(shots, "2-done-snackbar.png"))
        val end = System.currentTimeMillis() + 10_000
        while (fake.created().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(200)
        assertEquals("one completion for Dust the shelves reached the server", listOf(5L), fake.created().values.map { it.choreId })
        adb.waitFor("snackbar gone", 20_000) { n -> n.none { it.text == "Undo" } }

        adb.longPress(adb.dump().first { it.text == "Vacuum the living room" })
        adb.waitFor("date sheet") { n -> n.any { it.text == "Pick date" } }
        settle()
        adb.screenshot(File(shots, "3-date-sheet.png"))
        adb.shell("input keyevent KEYCODE_BACK")
        adb.waitFor("sheet closed") { n -> n.none { it.text == "Pick date" } }

        adb.tap(adb.dump().first { it.text == "Clean the oven" })
        adb.waitFor("chore page") { n -> n.any { it.text.contains("Used the oven spray") || it.desc.contains("Used the oven spray") } }
        settle()
        adb.screenshot(File(shots, "4-chore-page.png"))
        adb.shell("input keyevent KEYCODE_BACK")
        adb.waitFor("chore list again") { n -> n.any { it.text == "Wipe counters" } }

        openMenu("My households")
        adb.waitFor("households") { n -> n.any { it.desc == "Settings for Demo WG" } }
        settle()
        adb.screenshot(File(shots, "5-households.png"))
        adb.shell("input keyevent KEYCODE_BACK")
        adb.waitFor("chore list again") { n -> n.any { it.text == "Wipe counters" } }

        openMenu("Settings")
        adb.waitFor("settings") { n -> n.any { it.text == "Theme" } && n.any { it.text == "Chores due" } }
        settle()
        adb.screenshot(File(shots, "6-settings.png"))

        shots.listFiles()!!.forEach { f ->
            val img = ImageIO.read(f)
            assertTrue("${f.name} is ${img.width}x${img.height}", img.width >= 1080 && img.height >= 1920)
        }
    }

    private fun openMenu(item: String) {
        adb.tap(adb.waitFor("menu button") { n -> n.any { it.desc == "Menu" } }.first { it.desc == "Menu" })
        adb.tap(adb.waitFor("menu item $item") { n -> n.any { it.text == item } }.first { it.text == item })
    }

    private fun install(apk: File) {
        adb.run("uninstall", pkg)
        val r = adb.run("install", "-r", apk.path)
        check("Success" in r) { "install failed: $r" }
        adb.shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    private fun launch() {
        adb.shell("am start -W -n $pkg/.MainActivity")
        adb.waitFor("server picker") { n -> n.any { it.text == "Connect to your server" } }
    }

    private fun connect() {
        adb.tap(adb.dump().first { it.cls == "android.widget.EditText" })
        adb.shell("input keyevent KEYCODE_MOVE_END" + " KEYCODE_DEL".repeat(12))
        adb.shell("input text 'http://10.0.2.2:${fake.url.substringAfterLast(':')}'")
        adb.tap(adb.dump().first { it.text == "Connect" })
    }

    private fun hideKeyboard() {
        if ("mInputShown=true" in adb.shell("dumpsys input_method")) adb.shell("input keyevent KEYCODE_ESCAPE")
    }

    private fun settle() = Thread.sleep(1_500)

    private fun demoStatusBar() {
        adb.shell("settings put global sysui_demo_allowed 1")
        fun demo(args: String) = adb.shell("am broadcast -a com.android.systemui.demo -e command $args")
        demo("enter")
        demo("clock -e hhmm 1000")
        demo("battery -e level 100 -e plugged false")
        demo("network -e mobile hide")
        demo("network -e wifi show -e level 4 -e fully true")
        demo("notifications -e visible false")
    }

    /** The demo household of server/src/demo.rs, with dates relative to today. */
    private fun seedDemo() {
        data class Sample(val name: String, val cat: String, val iv: String, val ago: List<Long>, val note: String? = null)
        val samples = listOf(
            Sample("Wipe counters", "Kitchen", "3d", listOf(1, 4, 7)),
            Sample("Clean the oven", "Kitchen", "3m", listOf(60, 150, 240), "Used the oven spray"),
            Sample("Descale the kettle", "Kitchen", "1m", listOf(27, 58)),
            Sample("Vacuum the living room", "Living room", "1w", listOf(9, 16, 23)),
            Sample("Dust the shelves", "Living room", "2w", listOf(25, 40)),
            Sample("Water the plants", "Living room", "1w", listOf(20, 27, 34)),
            Sample("Clean the bathroom", "Bathroom", "1w", listOf(3, 10, 17)),
            Sample("Wash the shower curtain", "Bathroom", "6m", listOf(400)),
            Sample("Sort the paper recycling", "Hallway", "2w", emptyList()),
            Sample("Sweep the entrance", "Hallway", "1w", listOf(5, 12), "Muddy again"),
        )
        fake.oidcName = null
        fake.openRegistration = false
        fake.displayName = reviewer.displayName
        fake.me = reviewer
        fake.households.clear()
        fake.households += Household(1, "Demo WG", 1)
        fake.lastHousehold = 1
        fake.chores.clear()
        fake.completions.clear()
        fake.invites.clear()
        fake.members.clear()
        fake.members[1] = mutableListOf(Member(1, reviewer.displayName, "reviewer"))
        samples.forEachIndexed { i, s ->
            val id = i + 1L
            fake.chores += Chore(id = id, householdId = 1, name = s.name, category = s.cat, interval = s.iv)
            s.ago.forEachIndexed { k, d ->
                fake.completions["seed-$id-$k"] = FakeServer.Done(id, fake.today.minusDays(d).toString(), if (k == 0) s.note else null, reviewer)
            }
        }
    }

    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.first()} failed: $text" }
        return text
    }
}

/** A node of `uiautomator dump`, centre in screen pixels. */
data class UiNode(val text: String, val desc: String, val cls: String, val x: Int, val y: Int)

class Adb {
    fun run(vararg args: String): String {
        val p = ProcessBuilder(listOf("adb") + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return text
    }

    fun shell(cmd: String) = run("shell", cmd)

    fun tap(n: UiNode) = shell("input tap ${n.x} ${n.y}")

    fun longPress(n: UiNode) = shell("input swipe ${n.x} ${n.y} ${n.x} ${n.y} 1200")

    fun screenshot(to: File) {
        val p = ProcessBuilder("adb", "exec-out", "screencap", "-p").redirectOutput(to).start()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && to.length() > 0) { "screencap failed" }
    }

    fun dump(): List<UiNode> {
        repeat(5) {
            val r = shell("uiautomator dump /sdcard/wgc-ui.xml")
            if ("dumped to" in r) return parse(run("exec-out", "cat", "/sdcard/wgc-ui.xml"))
            Thread.sleep(500)
        }
        error("uiautomator dump failed")
    }

    fun waitFor(what: String, timeoutMs: Long = 20_000, cond: (List<UiNode>) -> Boolean): List<UiNode> {
        val end = System.currentTimeMillis() + timeoutMs
        var last = emptyList<UiNode>()
        while (System.currentTimeMillis() < end) {
            last = runCatching { dump() }.getOrDefault(emptyList())
            if (cond(last)) return last
            // a fresh emulator sometimes shows "System UI isn't responding"
            if (last.any { it.text.endsWith("isn't responding") }) last.firstOrNull { it.text == "Wait" }?.let(::tap)
            Thread.sleep(500)
        }
        error("timed out waiting for $what; screen: ${last.filter { it.text.isNotEmpty() || it.desc.isNotEmpty() }.joinToString { it.text.ifEmpty { it.desc } }}")
    }

    private fun parse(xml: String): List<UiNode> = Regex("<node [^>]*>").findAll(xml).map { m ->
        val a = m.value
        fun attr(n: String) = Regex("""\b$n="([^"]*)"""").find(a)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(attr("bounds"))!!.groupValues.drop(1).map { it.toInt() }
        UiNode(attr("text"), attr("content-desc"), attr("class"), (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    }.toList()

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", "\n").replace("&amp;", "&")
}
