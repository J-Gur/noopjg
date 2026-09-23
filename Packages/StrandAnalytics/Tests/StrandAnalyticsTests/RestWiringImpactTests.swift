import XCTest
@testable import StrandAnalytics

/// Behavioral validation of SL1/T1: score the SAME night for different sleeper HISTORIES and show
/// how Rest moves once real regularity + personalized need replace neutral-0.5 / fixed-8h. Prints an
/// old→new table for eyeballing magnitude/direction, and pins the load-bearing directions.
final class RestWiringImpactTests: XCTestCase {
    private typealias Rest = AnalyticsEngine.Rest

    // One fixed scored night so only (need, consistency) vary: 7h TST, 90% eff, 1h deep, 1.5h REM.
    private let tst = 7.0 * 3600, inbed = 7.0 * 3600 / 0.9, eff = 0.90
    private let deep = 1.0 * 3600, rem = 1.5 * 3600

    private func restOld() -> Double {
        Rest.composite(tstSeconds: tst, inBedSeconds: inbed, efficiency: eff,
                       restorativeSeconds: deep + rem, needHours: Rest.defaultNeedHours,
                       consistency: nil, deepSeconds: deep)   // nil → neutral 0.5
    }
    private func restNew(history: [Double], age: Int?) -> (rest: Double, need: Double, cons: Double) {
        let need = Rest.personalizedNeedHours(nightlyHours: history, age: age)
        let cons = VitalityEngine.sleepConsistency(nightlyHours: Array(history.suffix(28)))
        let rest = Rest.composite(tstSeconds: tst, inBedSeconds: inbed, efficiency: eff,
                                  restorativeSeconds: deep + rem, needHours: need,
                                  consistency: cons, deepSeconds: deep)
        return (rest, need, cons ?? -1)
    }

    func testArchetypeImpactTable() {
        let regular = Array(repeating: 8.0, count: 14)
        let irregular = (0..<14).map { $0 % 2 == 0 ? 5.0 : 10.0 }   // mean 7.5, high variance
        let chronicShort = Array(repeating: 5.5, count: 14)
        let longSleeper = Array(repeating: 9.2, count: 14)

        let old = restOld()
        let reg = restNew(history: regular, age: 30)
        let irr = restNew(history: irregular, age: 30)
        let shortS = restNew(history: chronicShort, age: 30)
        let longS = restNew(history: longSleeper, age: 30)

        print(String(format: "OLD (neutral 0.5, need 8h):            Rest %5.1f", old))
        print(String(format: "regular good sleeper:  need %.1f cons %.2f  Rest %5.1f", reg.need, reg.cons, reg.rest))
        print(String(format: "irregular sleeper:     need %.1f cons %.2f  Rest %5.1f", irr.need, irr.cons, irr.rest))
        print(String(format: "chronic short (5.5h):  need %.1f cons %.2f  Rest %5.1f", shortS.need, shortS.cons, shortS.rest))
        print(String(format: "long sleeper (9.2h):   need %.1f cons %.2f  Rest %5.1f", longS.need, longS.cons, longS.rest))

        // Load-bearing directions (SL1): a REGULAR history lifts Rest above an IRREGULAR one for the
        // identical night — regularity now actually matters.
        XCTAssertGreaterThan(reg.rest, irr.rest)
        // T1: chronic-short need is floored (never collapses toward the 5.5h deficit).
        XCTAssertGreaterThanOrEqual(shortS.need, 7.0)
        // T1: a genuine long sleeper's need exceeds the old fixed 8h (more demanding duration term).
        XCTAssertGreaterThan(longS.need, 8.0)
    }

    // MARK: - #1727: the `daily:`-based overload must ALSO forward need/consistency
    //
    // Everything above exercises the raw-seconds `composite(tstSeconds:...)` overload. But
    // `IntelligenceEngine.recomputeRecovery` / `recomputeChargeDrivers` / the persisted `sleep_performance`
    // write all call the DailyMetric-based `composite(daily:)` overload instead (the raw per-night streams
    // are gone by pass 2), and until #1727 they called it with NO needHours/consistency arguments at all —
    // silently falling back to this overload's own defaults (neutral 0.5, flat 8h) even though the real
    // personalized values were sitting in scope the whole time. Pins that the `daily:` overload actually
    // forwards them (not just that the raw-seconds one does), so that specific call shape can't regress.
    func testDailyOverloadThreadsRealNeedAndConsistency() throws {
        let d = DailyMetric(day: "2026-01-01", totalSleepMin: 420, efficiency: 0.90,
                            deepMin: 60, remMin: 90, lightMin: 270, disturbances: 2,
                            restingHr: 52, avgHrv: 60, recovery: nil, strain: nil, exerciseCount: nil)
        let withDefaults = try XCTUnwrap(Rest.composite(daily: d))
        // A personalized need BELOW the 8h default (raises the duration term) and a real consistency
        // ABOVE neutral 0.5 (raises the consistency term) each push the score up on their own, and
        // together push it up further , three unambiguous, tolerance-free directions that don't depend
        // on hand-computing the exact weighted sum.
        let needOnly = try XCTUnwrap(Rest.composite(daily: d, needHours: 7.5, consistency: nil))
        let consistencyOnly = try XCTUnwrap(Rest.composite(daily: d, needHours: Rest.defaultNeedHours,
                                                            consistency: 0.8))
        let both = try XCTUnwrap(Rest.composite(daily: d, needHours: 7.5, consistency: 0.8))
        XCTAssertGreaterThan(needOnly, withDefaults)
        XCTAssertGreaterThan(consistencyOnly, withDefaults)
        XCTAssertGreaterThan(both, needOnly)
        XCTAssertGreaterThan(both, consistencyOnly)
    }
}
