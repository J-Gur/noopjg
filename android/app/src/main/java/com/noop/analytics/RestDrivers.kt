package com.noop.analytics

import com.noop.data.DailyMetric

// RestDrivers.kt - the USER-FACING "why is my Rest what it is" breakdown for the Rest (sleep) score.
//
// Kotlin twin of the Swift RestDrivers.swift / `AnalyticsEngine.Rest.drivers`. UNLIKE Charge's
// marginal-delta framing (RecoveryDrivers.kt - a logistic score, so per-term deltas don't sum to the
// headline), Rest is a plain weighted linear sum - wDuration*durationScore + wEfficiency*efficiencyScore
// + wRestorative*restorativeScore + wConsistency*consistencyScore, each in [0,1], scaled to [0,100] - so
// each row's earnedPoints is the term's EXACT contribution and the four rows DO sum to the headline (mod
// rounding). No marginal recompute needed, no "doesn't sum to the score" caveat.
//
// HONESTY RULES (mirror RestScorer.rest exactly):
//   - Every scored night (tstSeconds > 0) gets all FOUR rows. Unlike Charge, there is no per-term
//     "missing input -> no row" gate here beyond that: duration/efficiency/restorative always have a
//     real value once there is any asleep time, and consistency ALWAYS has a value too - rest(...)
//     itself never drops the consistency term, it substitutes a neutral 0.5 when the caller supplies
//     none. So `consistency = null` here reads the SAME neutral 0.5 the score itself used, not an
//     absent row - a driver row can never disagree with what actually scored the night.
//   - earnedPoints/maxPoints come from the SAME weights `RestScorer.rest` uses, so they can never drift
//     from the headline's own weighting.
//
// Pure and side-effect-free (no clock, no I/O), so a fixture night pins the exact rows. No em-dashes.

/**
 * One semantic driver row behind the Rest (sleep) score. Presentation layers map its enums and
 * measurements to localized copy and locale-aware formatting.
 *
 * @property label stable semantic identity of the term.
 * @property earnedPoints this term's EXACT contribution to the 0-100 Rest score (rows sum to the
 *   headline, mod rounding - Rest is a linear weighted sum, not a logistic like Charge).
 * @property maxPoints the term's ceiling: weight * 100 (50 / 20 / 20 / 10), what a perfect night earns.
 * @property value the night's numeric value in [unit].
 * @property target the fixed target [value] was scored against (need hours for duration, the 50%
 *   restorative-share target), or null when the term reads against an implicit 100% rather than a
 *   separate target (efficiency, consistency).
 * @property unit semantic measurement unit shared by [value] and [target].
 * @property verdict semantic interpretation for presentation by the UI layer. Unlike Charge's verdicts,
 *   these don't argue a physiological direction (Rest doesn't "support/limit" itself) - they just say
 *   how much of the term's ceiling the night earned.
 */
enum class RestDriverLabel {
    SLEEP_DURATION,
    SLEEP_EFFICIENCY,
    RESTORATIVE_SLEEP,
    SLEEP_CONSISTENCY,
}

enum class RestDriverUnit {
    HOURS,
    PERCENT,
}

enum class RestDriverVerdict {
    MET_SLEEP_NEED,
    SHORT_OF_SLEEP_NEED,
    WELL_SHORT_OF_SLEEP_NEED,
    HIGH_EFFICIENCY,
    TYPICAL_EFFICIENCY,
    LOWER_EFFICIENCY,
    PLENTY_DEEP_AND_REM,
    TYPICAL_DEEP_AND_REM,
    LIGHT_DEEP_AND_REM,
    REGULAR_SCHEDULE,
    FAIRLY_REGULAR_SCHEDULE,
    IRREGULAR_SCHEDULE,
}

data class RestDriver(
    val label: RestDriverLabel,
    val earnedPoints: Int,
    val maxPoints: Int,
    val value: Double,
    val target: Double?,
    val unit: RestDriverUnit,
    val verdict: RestDriverVerdict,
)

object RestDrivers {

    /**
     * Build the ordered Rest driver list from the SAME raw inputs [RestScorer.rest] reads. Empty only
     * when there is no sleep at all (`tstSeconds <= 0`, mirroring `rest(...)`'s own null gate); otherwise
     * always four rows in a FIXED order (duration, efficiency, restorative, consistency) - unlike
     * Charge's biggest-mover-first, Rest's weights are fixed and well known, so a stable reading order
     * serves the user better than a shuffling one.
     *
     * The Swift twin is `AnalyticsEngine.Rest.drivers`.
     */
    fun drivers(
        tstSeconds: Double,
        efficiency: Double,
        restorativeSeconds: Double,
        needHours: Double,
        consistency: Double?,
        deepSeconds: Double?,
    ): List<RestDriver> {
        if (tstSeconds <= 0.0) return emptyList()
        fun earned(weight: Double, score: Double): Int {
            val pts = weight * score * 100.0
            return if (pts < 0.0) -Math.round(-pts).toInt() else Math.round(pts).toInt()
        }
        fun ceiling(weight: Double): Int = Math.round(weight * 100.0).toInt()

        val t = RestScorer.termScores(tstSeconds, efficiency, restorativeSeconds, needHours, consistency, deepSeconds)
        val needSeconds = maxOf(needHours, 0.1) * 3600.0

        return listOf(
            RestDriver(
                label = RestDriverLabel.SLEEP_DURATION,
                earnedPoints = earned(RestScorer.wDuration, t.duration),
                maxPoints = ceiling(RestScorer.wDuration),
                value = tstSeconds / 3600.0,
                target = needHours,
                unit = RestDriverUnit.HOURS,
                verdict = durationVerdict(ratio = tstSeconds / needSeconds),
            ),
            RestDriver(
                label = RestDriverLabel.SLEEP_EFFICIENCY,
                earnedPoints = earned(RestScorer.wEfficiency, t.efficiency),
                maxPoints = ceiling(RestScorer.wEfficiency),
                value = t.efficiency * 100.0,
                target = null,
                unit = RestDriverUnit.PERCENT,
                verdict = efficiencyVerdict(t.efficiency),
            ),
            RestDriver(
                label = RestDriverLabel.RESTORATIVE_SLEEP,
                earnedPoints = earned(RestScorer.wRestorative, t.restorative),
                maxPoints = ceiling(RestScorer.wRestorative),
                value = t.restorativeShare * 100.0,
                target = RestScorer.restorativeTargetShare * 100.0,
                unit = RestDriverUnit.PERCENT,
                verdict = restorativeVerdict(t.restorative),
            ),
            RestDriver(
                label = RestDriverLabel.SLEEP_CONSISTENCY,
                earnedPoints = earned(RestScorer.wConsistency, t.consistency),
                maxPoints = ceiling(RestScorer.wConsistency),
                value = t.consistency * 100.0,
                target = null,
                unit = RestDriverUnit.PERCENT,
                verdict = consistencyVerdict(t.consistency),
            ),
        )
    }

    /**
     * Convenience overload reading straight off a persisted [DailyMetric] (the pass-2 / display path,
     * same shape as [RestScorer.restFromDaily]) - what the Rest breakdown sheet actually calls. null
     * `totalSleepMin`/`efficiency` (no sleep that day) yields an empty list, same as the raw-seconds
     * overload's `tstSeconds <= 0` gate.
     */
    fun drivers(
        daily: DailyMetric,
        needHours: Double = RestScorer.defaultSleepNeedHours,
        consistency: Double? = null,
    ): List<RestDriver> {
        val tstMin = daily.totalSleepMin ?: return emptyList()
        val eff = daily.efficiency ?: return emptyList()
        if (tstMin <= 0.0) return emptyList()
        val tstSec = tstMin * 60.0
        val deepSec = (daily.deepMin ?: 0.0) * 60.0
        val restorativeSec = (daily.deepMin ?: 0.0) * 60.0 + (daily.remMin ?: 0.0) * 60.0
        return drivers(tstSec, eff, restorativeSec, needHours, consistency, deepSec)
    }

    // MARK: - Plain-English verdicts (no fabricated numbers; simple thirds). Every enum case here has a
    // matching R.string resource; when adding or rewording a verdict, add the identical case+string.

    private fun durationVerdict(ratio: Double): RestDriverVerdict = when {
        ratio >= 1.0 -> RestDriverVerdict.MET_SLEEP_NEED
        ratio >= 0.85 -> RestDriverVerdict.SHORT_OF_SLEEP_NEED
        else -> RestDriverVerdict.WELL_SHORT_OF_SLEEP_NEED
    }

    private fun efficiencyVerdict(eff: Double): RestDriverVerdict = when {
        eff >= 0.90 -> RestDriverVerdict.HIGH_EFFICIENCY
        eff >= 0.75 -> RestDriverVerdict.TYPICAL_EFFICIENCY
        else -> RestDriverVerdict.LOWER_EFFICIENCY
    }

    private fun restorativeVerdict(score: Double): RestDriverVerdict = when {
        score >= 0.90 -> RestDriverVerdict.PLENTY_DEEP_AND_REM
        score >= 0.60 -> RestDriverVerdict.TYPICAL_DEEP_AND_REM
        else -> RestDriverVerdict.LIGHT_DEEP_AND_REM
    }

    private fun consistencyVerdict(score: Double): RestDriverVerdict = when {
        score >= 0.65 -> RestDriverVerdict.REGULAR_SCHEDULE
        score >= 0.35 -> RestDriverVerdict.FAIRLY_REGULAR_SCHEDULE
        else -> RestDriverVerdict.IRREGULAR_SCHEDULE
    }
}
