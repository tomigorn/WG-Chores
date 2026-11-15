package ch.tomigorn.wgchores.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.domain.Intervals
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The texts the owner fixed: interval words in chip order, invite expiry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordsTest {
    private fun ctx(lang: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(conf)
    }

    private val ids = mapOf(
        "3d" to R.string.interval_3d, "1w" to R.string.interval_1w, "2w" to R.string.interval_2w, "1m" to R.string.interval_1m,
        "3m" to R.string.interval_3m, "6m" to R.string.interval_6m, "1y" to R.string.interval_1y,
    )

    private fun chips(lang: String) = Intervals.ORDER.map { ctx(lang).getString(ids.getValue(it)) }

    @Test fun intervalChipsInEnglish() = assertEquals(
        listOf("every 3 days", "every week", "every 2 weeks", "every month", "every 3 months", "every 6 months", "every year"),
        chips("en"),
    )

    @Test fun intervalChipsInGerman() = assertEquals(
        listOf("alle 3 Tage", "jede Woche", "alle 2 Wochen", "jeden Monat", "alle 3 Monate", "alle 6 Monate", "jedes Jahr"),
        chips("de"),
    )

    @Test fun neverBiweeklyNorEveryDay() {
        for (w in chips("en") + chips("de")) {
            assertFalse(w, w.contains("biweekly", true) || w.contains("every day", true) || w.contains("zweiwöchentlich", true))
        }
    }

    @Test fun nothingPickedMeansEveryMonth() {
        assertEquals("Nothing picked = every month", ctx("en").getString(R.string.edit_none_hint))
        assertEquals("Nichts gewählt = jeden Monat", ctx("de").getString(R.string.edit_none_hint))
    }

    @Test fun expiryTexts() {
        val en = ctx("en").resources
        val de = ctx("de").resources
        assertEquals("expires in 1 day", en.getQuantityString(R.plurals.expiry_days, 1, 1))
        assertEquals("expires in 6 days", en.getQuantityString(R.plurals.expiry_days, 6, 6))
        assertEquals("expires in 3 hours", en.getQuantityString(R.plurals.expiry_hours, 3, 3))
        assertEquals("läuft in 6 Tagen ab", de.getQuantityString(R.plurals.expiry_days, 6, 6))
        assertEquals("läuft in 1 Stunde ab", de.getQuantityString(R.plurals.expiry_hours, 1, 1))
    }

    @Test fun refusedEntryDeletes() {
        assertEquals("Couldn't delete the entry from 19 Oct; it stays.", ctx("en").getString(R.string.entry_delete_dropped, "19 Oct"))
        assertEquals("Couldn't delete 2 entries; they stay.", ctx("en").resources.getQuantityString(R.plurals.entry_delete_dropped_many, 2, 2))
        assertEquals("Der Eintrag vom 19. Okt. konnte nicht gelöscht werden und bleibt.", ctx("de").getString(R.string.entry_delete_dropped, "19. Okt."))
    }

    @Test fun usualGapKeepsNumberAndUnitTogether() {
        assertEquals("Usually done every\u00a0~9\u00a0days", ctx("en").resources.getQuantityString(R.plurals.chore_usual, 9, 9))
        assertEquals("Meist alle\u00a0~9\u00a0Tage erledigt", ctx("de").resources.getQuantityString(R.plurals.chore_usual, 9, 9))
    }
}
