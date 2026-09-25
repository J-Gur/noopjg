package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SHARED-CONTRACT Rest "What shaped it" driver rows (RestDrivers.drivers). Proves: every
 * scored night yields exactly four rows in a fixed order; earnedPoints/maxPoints come from the SAME
 * weights `RestScorer.rest` uses (so the four rows sum to the headline, mod rounding - unlike Charge's
 * marginal deltas); the no-sleep gate yields an empty list; and the fixed test scenario's exact numbers
 * are pinned as the ORACLE for the Swift RestDriversTests (`testFixedScenarioMatchesKotlinOracle`).
 *
 * #1727-ORACLE-DIRECTION: this repo's rule is Swift-compiled-as-oracle for Kotlin (CLAUDE.md). Neither
 * Xcode nor a working `swift build`/`swift test` was available in the environment this test was written
 * in (no Xcode install; even the pure StrandAnalytics SPM package failed to link its manifest), so the
 * direction is REVERSED here out of necessity: this Kotlin test is the one that actually ran, and its
 * `testFixedScenario...` numbers below are what the Swift twin's literals were hand-derived from and
 * must be re-verified against once Swift can actually be compiled. Say so plainly rather than silently
 * presenting this as the normal direction.
 */
class RestDriversTest {

    @Test fun everyScoredNightYieldsFourRowsInFixedOrder() {
        val rows = RestDrivers.drivers(
            tstSeconds = 7.0 * 3600, efficiency = 0.85, restorativeSeconds = 2.0 * 3600,
            needHours = 8.0, consistency = 0.6, deepSeconds = 1.0 * 3600,
        )
        assertEquals(4, rows.size)
        assertEquals(
            listOf(
                RestDriverLabel.SLEEP_DURATION, RestDriverLabel.SLEEP_EFFICIENCY,
                RestDriverLabel.RESTORATIVE_SLEEP, RestDriverLabel.SLEEP_CONSISTENCY,
            ),
            rows.map { it.label },
        )
    }

    @Test fun noSleepYieldsEmptyList() {
        assertEquals(0, RestDrivers.drivers(0.0, 0.9, 0.0, 8.0, 0.5, 0.0).size)
        assertEquals(0, RestDrivers.drivers(-1.0, 0.9, 0.0, 8.0, 0.5, 0.0).size)
    }

    @Test fun maxPointsAreTheFixedWeightCeilings() {
        val rows = RestDrivers.drivers(
            tstSeconds = 7.0 * 3600, efficiency = 0.85, restorativeSeconds = 2.0 * 3600,
            needHours = 8.0, consistency = 0.6, deepSeconds = 1.0 * 3600,
        )
        assertEquals(50, rows.first { it.label == RestDriverLabel.SLEEP_DURATION }.maxPoints)
        assertEquals(20, rows.first { it.label == RestDriverLabel.SLEEP_EFFICIENCY }.maxPoints)
        assertEquals(20, rows.first { it.label == RestDriverLabel.RESTORATIVE_SLEEP }.maxPoints)
        assertEquals(10, rows.first { it.label == RestDriverLabel.SLEEP_CONSISTENCY }.maxPoints)
    }

    @Test fun earnedPointsSumsToWithinRoundingOfTheHeadline() {
        val tst = 7.0 * 3600
        val eff = 0.85
        val restorative = 2.0 * 3600
        val need = 8.0
        val cons = 0.6
        val deep = 1.0 * 3600
        val rows = RestDrivers.drivers(tst, eff, restorative, need, cons, deep)
        val headline = RestScorer.rest(
            asleepSeconds = tst, efficiency = eff, deepSeconds = deep, remSeconds = restorative - deep,
            sleepNeedHours = need, consistency = cons,
        )!!
        val summedEarned = rows.sumOf { it.earnedPoints }
        // Rest is a linear weighted sum (unlike Charge), so summing the four independently-rounded terms
        // can differ from the headline's own single rounding by at most a small slack - not "any amount".
        assertTrue(
            "summed earned points ($summedEarned) should track the headline ($headline) closely",
            kotlin.math.abs(summedEarned - headline) <= 2.0,
        )
    }

    /**
     * Fixed scenario, exact numbers - the Swift/Kotlin cross-platform oracle for this PR (see the class
     * doc for why the usual oracle direction is reversed here). 6h30 TST, 88% efficiency, 55 min deep +
     * 85 min REM, 7h45 personal need, 0.7 consistency.
     */
    @Test fun testFixedScenarioMatchesKotlinOracle() {
        val tstSeconds = 6.5 * 3600       // 23400
        val efficiency = 0.88
        val deepSeconds = 55.0 * 60       // 3300
        val remSeconds = 85.0 * 60        // 5100
        val restorativeSeconds = deepSeconds + remSeconds   // 8400
        val needHours = 7.75
        val consistency = 0.7

        val rows = RestDrivers.drivers(
            tstSeconds, efficiency, restorativeSeconds, needHours, consistency, deepSeconds,
        )
        assertEquals(4, rows.size)
        val duration = rows[0]
        val eff = rows[1]
        val restorative = rows[2]
        val cons = rows[3]

        assertEquals(42, duration.earnedPoints)
        assertEquals(6.5, duration.value, 1e-9)
        assertEquals(7.75, duration.target!!, 1e-9)
        assertEquals(RestDriverVerdict.WELL_SHORT_OF_SLEEP_NEED, duration.verdict)

        assertEquals(18, eff.earnedPoints)
        assertEquals(88.0, eff.value, 1e-9)
        assertEquals(RestDriverVerdict.TYPICAL_EFFICIENCY, eff.verdict)

        assertEquals(14, restorative.earnedPoints)
        assertEquals(50.0, restorative.target!!, 1e-9)
        assertEquals(RestDriverVerdict.TYPICAL_DEEP_AND_REM, restorative.verdict)

        assertEquals(7, cons.earnedPoints)
        assertEquals(70.0, cons.value, 1e-9)
        assertEquals(RestDriverVerdict.REGULAR_SCHEDULE, cons.verdict)
    }
}
