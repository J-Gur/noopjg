import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Rest breakdown presentation (pure, testable)
//
// #1727: LANE 2 (iOS UI) presentation for the "What shaped it" Rest breakdown, mirroring
// ChargeBreakdownFormat.swift's own shape. Every helper here is PURE (no SwiftUI state, no I/O); the
// views below consume them. Presentation only: nothing here recomputes a score or a term's points -
// those arrive from the engine (`RestDriver`) and are surfaced verbatim.
//
// No fabricated numbers, no em-dashes. Design-system tokens only.

enum RestBreakdownFormat {

    /// The chip label for a term's earned-vs-ceiling points, e.g. "42/50 pts". UNLIKE Charge's signed
    /// delta (which can be negative), Rest's earnedPoints is always in [0, maxPoints] - the chip never
    /// carries a sign. Pure.
    static func pointsLabel(_ driver: RestDriver) -> String {
        String(localized: "\(driver.earnedPoints)/\(driver.maxPoints) pts")
    }

    /// The chip/bar tint for a term, banded by how much of its OWN ceiling it earned (not a
    /// supporting/limiting direction - Rest doesn't argue one the way Charge's signed delta does).
    static func tint(_ driver: RestDriver) -> Color {
        let fraction = driver.maxPoints > 0 ? Double(driver.earnedPoints) / Double(driver.maxPoints) : 0
        if fraction >= 0.75 { return StrandPalette.statusPositive }
        if fraction >= 0.5 { return StrandPalette.textSecondary }
        return StrandPalette.statusCritical
    }

    /// VoiceOver phrasing of one driver row: label, earned/max points, value vs target, verdict. Built
    /// from the engine row verbatim (no recompute). Pure.
    static func driverAccessibilityLabel(_ d: RestDriver) -> String {
        // The engine's label + verdict are catalog KEYS (see RestDrivers.swift), same convention as
        // ChargeBreakdownFormat.driverAccessibilityLabel - look each up first so the spoken sentence is
        // fully localized, not half-English.
        let label = String(localized: String.LocalizationValue(d.label))
        let verdict = String(localized: String.LocalizationValue(d.verdict))
        let points = String(localized: "\(d.earnedPoints) of \(d.maxPoints) points")
        if d.targetText.isEmpty {
            return String(localized: "\(label): \(points). \(d.valueText). \(verdict).")
        }
        return String(localized: "\(label): \(points). \(d.valueText), \(d.targetText). \(verdict).")
    }
}

// MARK: - A1: "What shaped it" Rest breakdown

/// The "What shaped it" breakdown rendered under the Rest ring: a header, then one row per
/// engine-supplied `RestDriver` in a FIXED order (duration, efficiency, restorative, consistency) -
/// unlike Charge's biggest-mover-first, Rest's weights are fixed and well known, so a stable reading
/// order serves the user better. Every value is surfaced verbatim from the engine. The caller GATES on
/// a non-empty driver list, so this view assumes at least one row.
struct RestBreakdownSection: View {
    let drivers: [RestDriver]

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
            Divider().overlay(StrandPalette.hairline)
            Text("What shaped it").strandOverline()
            VStack(spacing: NoopMetrics.rowSpacing) {
                ForEach(Array(drivers.enumerated()), id: \.offset) { _, driver in
                    RestDriverRow(driver: driver)
                }
            }
        }
    }
}

/// One Rest driver row: an earned/max points chip (always non-negative), the value vs target, a thin
/// progress bar filled to THIS row's own earned/ceiling fraction (not scaled against the other rows -
/// unlike Charge's cross-row magnitude bar, each Rest term's own ceiling is the meaningful comparison,
/// since the four weights are fixed and well known rather than a "which one moved the score" question),
/// and the plain-English verdict line.
struct RestDriverRow: View {
    let driver: RestDriver

    private var chipText: String { RestBreakdownFormat.pointsLabel(driver) }
    private var chipHue: Color { RestBreakdownFormat.tint(driver) }

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(LocalizedStringKey(driver.label))
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
                Spacer(minLength: 8)
                Text(chipText)
                    .font(StrandFont.captionNumber)
                    .foregroundStyle(chipHue)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(chipHue.opacity(0.14), in: Capsule(style: .continuous))
            }
            // value vs target - the target line is omitted for terms with no separate target (efficiency
            // / consistency, which read against an implicit 100%).
            HStack(spacing: 6) {
                Text(driver.valueText)
                    .font(StrandFont.captionNumber)
                    .foregroundStyle(StrandPalette.textSecondary)
                if !driver.targetText.isEmpty {
                    Text("·").font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                    Text(driver.targetText)
                        .font(StrandFont.caption)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                Spacer(minLength: 0)
            }
            PipBar(value: Double(driver.earnedPoints), range: 0...Double(max(1, driver.maxPoints)),
                  segments: 16, tint: chipHue, height: 6)
                .accessibilityHidden(true)
            Text(LocalizedStringKey(driver.verdict))
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(RestBreakdownFormat.driverAccessibilityLabel(driver))
    }
}
