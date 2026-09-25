import Foundation

// RestDrivers.swift - the ordered "why is my Rest what it is" driver list.
//
// SHARED CONTRACT (engine <-> iOS UI <-> Android): the Rest (sleep) result gains an ordered list of
// drivers, one row per term that fed the composite. UNLIKE Charge's marginal-delta framing (a logistic
// score, so per-term deltas don't sum to the headline - see ChargeDrivers.swift), Rest is a plain
// weighted linear sum - wDuration*durationScore + wEfficiency*efficiencyScore +
// wRestorative*restorativeScore + wConsistency*consistencyScore, each in [0,1], scaled to [0,100] - so
// each row's earnedPoints is the term's EXACT contribution and the four rows DO sum to the headline
// (mod rounding). No marginal recompute needed, no "doesn't sum to the score" caveat.
//
// Reuses the SAME four term computations `Rest.subScoreLine` (RestSubScoreTrace.swift) already emits as
// a terse diagnostic string; that function now builds its string FROM these rows rather than
// recomputing the weighting a second time.
//
// HONESTY RULES (mirror composite(...) exactly):
//   - Every scored night (tstSeconds > 0) gets all FOUR rows. Unlike Charge, there is no per-term
//     "missing input -> no row" gate here beyond that: duration/efficiency/restorative always have a
//     real value once there is any asleep time, and consistency ALWAYS has a value too - composite(...)
//     itself never drops the consistency term, it substitutes a neutral 0.5 when the caller supplies
//     none (see AnalyticsEngine.swift's Rest doc). So `consistency: nil` here reads the SAME neutral
//     0.5 the score itself used, not an absent row - a driver row can never disagree with what actually
//     scored the night.
//   - earnedPoints/maxPoints come from the SAME weights composite(...) uses (public static lets on
//     Rest), so they can never drift from the headline's own weighting.
//
// Pure and side-effect-free: no clock, no I/O. The Kotlin twin is RestScorer.drivers. No em-dashes.

/// One row of the Rest driver breakdown (SHARED CONTRACT shape).
public struct RestDriver: Equatable, Sendable {
    /// Human label for the term, e.g. "Sleep duration".
    public let label: String
    /// This term's EXACT contribution to the 0-100 Rest score, in points. The four rows sum to the
    /// headline (mod rounding) - Rest is a linear weighted sum, not a logistic like Charge.
    public let earnedPoints: Int
    /// The term's ceiling: weight * 100 (50 / 20 / 20 / 10), i.e. what a perfect night would earn here.
    public let maxPoints: Int
    /// The measured value, formatted with units, e.g. "6h12".
    public let valueText: String
    /// The fixed target this value was scored against, e.g. "8h00 need". Empty for efficiency /
    /// consistency, which read against an implicit 100% rather than a separate target value.
    public let targetText: String
    /// Short plain-English read of this term, e.g. "met your sleep need". Unlike Charge's verdicts,
    /// these don't argue a physiological direction (Rest doesn't "support/limit" itself) - they just
    /// say how much of the term's ceiling the night earned.
    public let verdict: String

    public init(label: String, earnedPoints: Int, maxPoints: Int, valueText: String,
                targetText: String, verdict: String) {
        self.label = label
        self.earnedPoints = earnedPoints
        self.maxPoints = maxPoints
        self.valueText = valueText
        self.targetText = targetText
        self.verdict = verdict
    }
}

extension AnalyticsEngine.Rest {

    // MARK: - Plain-English verdicts (no fabricated numbers; simple thirds)
    //
    // Every returned literal is also a runtime localization key consumed by RestBreakdownFormat (iOS).
    // When adding or rewording a verdict, add the identical key to Strand's Localizable.xcstrings;
    // Tools/i18n_audit.py enforces that engine-to-catalog contract.

    static func durationVerdict(ratio: Double) -> String {
        if ratio >= 1.0 { return "met your sleep need" }
        if ratio >= 0.85 { return "a bit short of your sleep need" }
        return "well short of your sleep need"
    }
    static func efficiencyVerdict(_ eff: Double) -> String {
        if eff >= 0.90 { return "high sleep efficiency" }
        if eff >= 0.75 { return "typical sleep efficiency" }
        return "lower sleep efficiency"
    }
    static func restorativeVerdict(_ score: Double) -> String {
        if score >= 0.90 { return "plenty of deep and REM sleep" }
        if score >= 0.60 { return "a typical mix of deep and REM sleep" }
        return "light on deep and REM sleep"
    }
    static func consistencyVerdict(_ score: Double) -> String {
        if score >= 0.65 { return "a regular sleep schedule" }
        if score >= 0.35 { return "a fairly regular sleep schedule" }
        return "an irregular sleep schedule"
    }

    /// "6h12" from a fractional hour count. Local to this file: the Rest breakdown is the only place
    /// duration reads as hours+minutes rather than a bare number, so it isn't worth promoting to a
    /// shared formatter yet.
    static func hoursMinutesText(_ hours: Double) -> String {
        let totalMin = Int((hours * 60.0).rounded())
        return "\(totalMin / 60)h\(String(format: "%02d", totalMin % 60))"
    }

    /// The four raw [0,1] term scores + the deep-adequacy factor, computed ONCE from the raw inputs.
    /// Shared by `drivers(...)` (which weights + rounds these into points) and `Rest.subScoreLine`
    /// (which formats them as raw fractions for the diagnostic trace) so the arithmetic exists in
    /// exactly one place and `subScoreLine`'s byte format is unchanged by this file's existence.
    /// Internal (not public): implementation detail, not part of the SHARED CONTRACT shape.
    static func termScores(tstSeconds: Double, efficiency: Double, restorativeSeconds: Double,
                          needHours: Double, consistency: Double?,
                          deepSeconds: Double?) -> (duration: Double, efficiency: Double,
                                                    restorative: Double, consistency: Double,
                                                    deepFactor: Double, restorativeShare: Double) {
        func clamp01(_ x: Double) -> Double { max(0.0, min(1.0, x)) }
        let needSeconds = max(needHours, 0.1) * 3600.0
        let durationScore = clamp01(tstSeconds / needSeconds)
        let efficiencyScore = clamp01(efficiency)
        // Deep-adequacy factor, byte-identical to composite(...)'s own.
        let deepFactor: Double = {
            guard let deep = deepSeconds, tstSeconds > 0, deepShareTarget > 0 else { return 1.0 }
            let adequacy = clamp01((deep / tstSeconds) / deepShareTarget)
            return deepFloorFactor + (1.0 - deepFloorFactor) * adequacy
        }()
        let restorativeShare = tstSeconds > 0 ? restorativeSeconds / tstSeconds : 0.0
        let restorativeScore = tstSeconds > 0
            ? clamp01(restorativeShare / restorativeTarget) * deepFactor
            : 0.0
        let consistencyScore = clamp01(consistency ?? neutralConsistency)
        return (durationScore, efficiencyScore, restorativeScore, consistencyScore, deepFactor,
               restorativeShare)
    }

    /// Build the ordered Rest driver list from the SAME raw inputs `composite(tstSeconds:...)` reads.
    /// Empty only when there is no sleep at all (`tstSeconds <= 0`, mirroring `composite`'s own nil
    /// gate); otherwise always four rows (see the honesty note in this file's header for why Rest, unlike
    /// Charge, has no per-term missing-input gate beyond that). Rows are in a fixed order (duration,
    /// efficiency, restorative, consistency) - unlike Charge's biggest-mover-first, Rest's weights are
    /// fixed and well known, so a stable reading order serves the user better than a shuffling one.
    ///
    /// The Kotlin twin is `RestScorer.drivers`.
    public static func drivers(tstSeconds: Double,
                               efficiency: Double,
                               restorativeSeconds: Double,
                               needHours: Double,
                               consistency: Double?,
                               deepSeconds: Double?) -> [RestDriver] {
        guard tstSeconds > 0 else { return [] }
        func earned(_ weight: Double, _ score: Double) -> Int {
            Int((weight * score * 100.0).rounded(.toNearestOrAwayFromZero))
        }
        func ceiling(_ weight: Double) -> Int {
            Int((weight * 100.0).rounded(.toNearestOrAwayFromZero))
        }
        let t = termScores(tstSeconds: tstSeconds, efficiency: efficiency,
                          restorativeSeconds: restorativeSeconds, needHours: needHours,
                          consistency: consistency, deepSeconds: deepSeconds)
        let needSeconds = max(needHours, 0.1) * 3600.0

        return [
            RestDriver(label: "Sleep duration",
                      earnedPoints: earned(wDuration, t.duration),
                      maxPoints: ceiling(wDuration),
                      valueText: hoursMinutesText(tstSeconds / 3600.0),
                      targetText: "\(hoursMinutesText(needHours)) need",
                      verdict: durationVerdict(ratio: tstSeconds / needSeconds)),
            RestDriver(label: "Sleep efficiency",
                      earnedPoints: earned(wEfficiency, t.efficiency),
                      maxPoints: ceiling(wEfficiency),
                      valueText: "\(Int((t.efficiency * 100).rounded()))%",
                      targetText: "",
                      verdict: efficiencyVerdict(t.efficiency)),
            RestDriver(label: "Restorative sleep",
                      earnedPoints: earned(wRestorative, t.restorative),
                      maxPoints: ceiling(wRestorative),
                      valueText: "\(Int((t.restorativeShare * 100).rounded()))%",
                      targetText: "\(Int(restorativeTarget * 100))% target",
                      verdict: restorativeVerdict(t.restorative)),
            RestDriver(label: "Sleep consistency",
                      earnedPoints: earned(wConsistency, t.consistency),
                      maxPoints: ceiling(wConsistency),
                      valueText: "\(Int((t.consistency * 100).rounded()))%",
                      targetText: "",
                      verdict: consistencyVerdict(t.consistency)),
        ]
    }

    /// Convenience overload reading straight off a persisted `DailyMetric` (the pass-2 / display path,
    /// same shape as `composite(daily:needHours:consistency:)`) - what the Rest breakdown sheet actually
    /// calls. nil `totalSleepMin`/`efficiency` (no sleep that day) yields an empty list, same as the
    /// raw-seconds overload's `tstSeconds <= 0` gate.
    public static func drivers(daily d: DailyMetric, needHours: Double = defaultNeedHours,
                               consistency: Double? = nil) -> [RestDriver] {
        guard let tstMin = d.totalSleepMin, tstMin > 0, let eff = d.efficiency else { return [] }
        let tstSec = tstMin * 60.0
        let deepSec = (d.deepMin ?? 0) * 60.0
        let restorativeSec = (d.deepMin ?? 0) * 60.0 + (d.remMin ?? 0) * 60.0
        return drivers(tstSeconds: tstSec, efficiency: eff, restorativeSeconds: restorativeSec,
                      needHours: needHours, consistency: consistency, deepSeconds: deepSec)
    }
}
