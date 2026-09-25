import XCTest
@testable import StrandAnalytics

/// Tests for the SHARED-CONTRACT Rest "What shaped it" driver rows (`AnalyticsEngine.Rest.drivers`).
/// Proves: every scored night yields exactly four rows in a fixed order; earnedPoints/maxPoints come
/// from the SAME weights `composite(...)` uses (so the four rows sum to the headline, mod rounding -
/// unlike Charge's marginal deltas); the no-sleep gate yields an empty list; and the fixed test
/// scenario's exact numbers match the Kotlin oracle (see below). Twin of the Android RestDriversTest.
/// No em-dashes.
///
/// #1727-ORACLE-DIRECTION: this repo's rule is Swift-compiled-as-oracle for Kotlin (CLAUDE.md). Neither
/// Xcode nor a working `swift build`/`swift test` was available in the environment this file was written
/// in (no Xcode install; even the pure StrandAnalytics SPM package failed to link its manifest), so this
/// test could NOT be run here. The direction is reversed out of necessity: `testFixedScenarioMatchesKotlinOracle`'s
/// literals were taken from the Kotlin `RestDriversTest.testFixedScenarioMatchesKotlinOracle`, which DID
/// run and pass, on the understanding that `termScores(...)` is a straight line-for-line port of Kotlin's
/// `RestScorer.termScores` (same clamps, same order of operations, same constants) - the same class of
/// claim `RestWiringImpactTests`/`RestWiringImpactTest` already establish for the underlying weights. This
/// file still needs a REAL `swift test` run before being trusted; say so plainly rather than silently
/// presenting this as the normal, already-verified oracle direction.
private typealias Rest = AnalyticsEngine.Rest

final class RestDriversTests: XCTestCase {

    func testEveryScoredNightYieldsFourRowsInFixedOrder() {
        let rows = Rest.drivers(tstSeconds: 7.0 * 3600, efficiency: 0.85,
                                restorativeSeconds: 2.0 * 3600, needHours: 8.0,
                                consistency: 0.6, deepSeconds: 1.0 * 3600)
        XCTAssertEqual(rows.count, 4)
        XCTAssertEqual(rows.map(\.label),
                       ["Sleep duration", "Sleep efficiency", "Restorative sleep", "Sleep consistency"])
    }

    func testNoSleepYieldsEmptyList() {
        XCTAssertTrue(Rest.drivers(tstSeconds: 0, efficiency: 0.9, restorativeSeconds: 0,
                                   needHours: 8.0, consistency: 0.5, deepSeconds: 0).isEmpty)
        XCTAssertTrue(Rest.drivers(tstSeconds: -1, efficiency: 0.9, restorativeSeconds: 0,
                                   needHours: 8.0, consistency: 0.5, deepSeconds: 0).isEmpty)
    }

    func testMaxPointsAreTheFixedWeightCeilings() {
        let rows = Rest.drivers(tstSeconds: 7.0 * 3600, efficiency: 0.85,
                                restorativeSeconds: 2.0 * 3600, needHours: 8.0,
                                consistency: 0.6, deepSeconds: 1.0 * 3600)
        XCTAssertEqual(rows[0].maxPoints, 50)   // duration
        XCTAssertEqual(rows[1].maxPoints, 20)   // efficiency
        XCTAssertEqual(rows[2].maxPoints, 20)   // restorative
        XCTAssertEqual(rows[3].maxPoints, 10)   // consistency
    }

    func testEarnedPointsSumsToWithinRoundingOfTheHeadline() {
        let tst = 7.0 * 3600, eff = 0.85, restorative = 2.0 * 3600
        let need = 8.0, cons = 0.6, deep = 1.0 * 3600
        let rows = Rest.drivers(tstSeconds: tst, efficiency: eff, restorativeSeconds: restorative,
                                needHours: need, consistency: cons, deepSeconds: deep)
        let headline = Rest.composite(tstSeconds: tst, inBedSeconds: tst / max(eff, 0.01),
                                      efficiency: eff, restorativeSeconds: restorative,
                                      needHours: need, consistency: cons, deepSeconds: deep)
        let summedEarned = rows.reduce(0) { $0 + $1.earnedPoints }
        // Rest is a linear weighted sum (unlike Charge), so summing the four independently-rounded terms
        // can differ from the headline's own single rounding by at most a small slack, not "any amount".
        XCTAssertLessThanOrEqual(abs(Double(summedEarned) - headline), 2.0)
    }

    /// Fixed scenario, exact numbers - the Swift/Kotlin cross-platform oracle for this PR (see the file
    /// doc for why the usual oracle direction is reversed here). 6h30 TST, 88% efficiency, 55 min deep +
    /// 85 min REM, 7h45 personal need, 0.7 consistency. Byte-identical to the Kotlin
    /// `testFixedScenarioMatchesKotlinOracle`, which is the one that actually ran.
    func testFixedScenarioMatchesKotlinOracle() {
        let tstSeconds = 6.5 * 3600       // 23400
        let efficiency = 0.88
        let deepSeconds = 55.0 * 60       // 3300
        let remSeconds = 85.0 * 60        // 5100
        let restorativeSeconds = deepSeconds + remSeconds   // 8400
        let needHours = 7.75
        let consistency = 0.7

        let rows = Rest.drivers(tstSeconds: tstSeconds, efficiency: efficiency,
                                restorativeSeconds: restorativeSeconds, needHours: needHours,
                                consistency: consistency, deepSeconds: deepSeconds)
        XCTAssertEqual(rows.count, 4)
        let duration = rows[0], eff = rows[1], restorative = rows[2], cons = rows[3]

        XCTAssertEqual(duration.earnedPoints, 42)
        XCTAssertEqual(duration.valueText, "6h30")
        XCTAssertEqual(duration.targetText, "7h45 need")
        XCTAssertEqual(duration.verdict, "well short of your sleep need")

        XCTAssertEqual(eff.earnedPoints, 18)
        XCTAssertEqual(eff.valueText, "88%")
        XCTAssertEqual(eff.verdict, "typical sleep efficiency")

        XCTAssertEqual(restorative.earnedPoints, 14)
        XCTAssertEqual(restorative.targetText, "50% target")
        XCTAssertEqual(restorative.verdict, "a typical mix of deep and REM sleep")

        XCTAssertEqual(cons.earnedPoints, 7)
        XCTAssertEqual(cons.valueText, "70%")
        XCTAssertEqual(cons.verdict, "a regular sleep schedule")
    }
}
