package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [excludeFutureDatedRows], the future-date guard [buildVitalDetail] applies before building
 * its "Latest" reading: a row dated AFTER "today" must never survive into the detail screen's trend,
 * matching the guard [lastScoredRecoveryDay]'s Today-tile carry-over already had. Before this guard,
 * the Today tile (carry-over-guarded) and this screen (unguarded) could read the SAME merged `days`
 * list and disagree whenever a stray future/mis-keyed row existed — not a staleness bug, since a fresh
 * app relaunch re-reads the same persisted rows and reproduces it identically.
 *
 * [buildVitalDetail] itself resolves strings via `uiString`/`NoopApplication` and cannot run in a plain
 * JVM test — the arrangement `SkinTempAbsoluteDisplayTest`/`Spo2MissingCaptionTest` already use for the
 * same reason — so the guard is exercised through this pure seam instead.
 */
class HealthScreenVitalDetailTest {

    private fun row(day: String) = DailyMetric(deviceId = "my-whoop", day = day)

    @Test
    fun excludesRowsDatedAfterToday() {
        val days = listOf(row("2026-09-06"), row("2026-09-07"), row("2026-09-09"), row("2026-09-10"))
        assertEquals(
            listOf("2026-09-06", "2026-09-07"),
            excludeFutureDatedRows(days, today = "2026-09-08").map { it.day },
        )
    }

    @Test
    fun keepsRowDatedExactlyToday() {
        // The guard is `day <= today`, not `day < today` — today's own (already-scored) row must still
        // count, the ordinary same-day case.
        val days = listOf(row("2026-09-07"), row("2026-09-08"))
        assertEquals(
            listOf("2026-09-07", "2026-09-08"),
            excludeFutureDatedRows(days, today = "2026-09-08").map { it.day },
        )
    }

    @Test
    fun allFutureRowsYieldsEmptyList() {
        val days = listOf(row("2026-09-09"), row("2026-09-10"))
        assertEquals(emptyList<String>(), excludeFutureDatedRows(days, today = "2026-09-08").map { it.day })
    }

    @Test
    fun emptyInputYieldsEmptyOutput() {
        assertEquals(emptyList<String>(), excludeFutureDatedRows(emptyList(), today = "2026-09-08"))
    }

    @Test
    fun preservesOrderAndDoesNotDropPastRows() {
        // Order-stable, and a long past history is untouched — only the tail past "today" is cut.
        val days = (1..5).map { row("2026-09-0$it") } + row("2026-09-09")
        val kept = excludeFutureDatedRows(days, today = "2026-09-08")
        assertEquals(days.dropLast(1).map { it.day }, kept.map { it.day })
    }
}
