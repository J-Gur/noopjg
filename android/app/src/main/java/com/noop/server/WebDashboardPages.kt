package com.noop.server

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.Baselines
import com.noop.analytics.ChargeDriver
import com.noop.analytics.ChargeDriverLabel
import com.noop.analytics.ChargeDriverUnit
import com.noop.analytics.FitnessAgeEngine
import com.noop.analytics.FitnessAgeResult
import com.noop.analytics.RecoveryDrivers
import com.noop.analytics.RestScorer
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WorkoutRow
import com.noop.ui.AppViewModel
import com.noop.ui.DisplayText
import com.noop.ui.NoopPrefs
import com.noop.ui.PersistedSegment
import com.noop.ui.ProfileStore
import com.noop.ui.VitalReading
import com.noop.ui.carriedCaption
import com.noop.ui.lastScoredRecoveryDay
import com.noop.ui.mergeReadings
import com.noop.ui.mergeStepsReadings
import com.noop.ui.parsePersistedSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 4-tab NOOP companion web app: Today / Workouts / Sleep / Health. Every page is a pure function
 * of [AppViewModel] state — [WorkoutHttpServer] resolves the live instance and the "app not open"
 * fallback before ever calling in here.
 *
 * Data sources, in order of preference (real app logic over approximation, per the plan this was
 * built from):
 *  - `vm.today.value` (a live [DailyMetric]) for anything already computed nightly: recovery, strain,
 *    resting HR, sleep summary, SpO2/skin-temp/resp/SDNN, steps, active kcal.
 *  - `vm.repo.daysMerged(deviceId)` — the SAME merged multi-day history the real screens fold into
 *    baselines/trends — for Recovery Drivers, Fitness Age coverage, and the Health tab sparklines.
 *  - `vm.repo.computedSleepSessionsUnion(...)` + the internal (module-visible, not private)
 *    `parsePersistedSegments` from `com.noop.ui` for the REAL per-segment hypnogram, falling back to a
 *    proportional Deep/REM/Light bar only when a night has no stored segment JSON (imported nights,
 *    mainly) — not as a default shortcut.
 */

internal enum class Tab(val route: String, val label: String) {
    TODAY("/today", "Today"),
    WORKOUTS("/workouts", "Workouts"),
    SLEEP("/sleep", "Sleep"),
    HEALTH("/health", "Health"),
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Pages
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** [todayPage]'s resolved read-outs — the same shape [buildRecoveryDrivers]/[recoveryRingSvg]/[statTile]
 *  need, computed together in one IO pass so the carry-over and live-blend logic below share the [days]
 *  history query instead of fetching it twice. */
private data class TodayResolved(
    val drivers: List<ChargeDriver>,
    val recovery: Double?,
    val recoveryCarryCaption: String?,
    val strain: Double?,
)

/** Resolves a [DisplayText] to a plain string outside Compose — the non-composable twin of
 *  `TodayScreen.kt`'s private `DisplayText.localized()`, which can't be called from here (different
 *  package, and `@Composable`). [com.noop.ui.uiString] itself is already a plain function, so this is
 *  just the same `when` without the Compose requirement. */
private fun displayTextString(text: DisplayText): String = when (text) {
    is DisplayText.Resource -> com.noop.ui.uiString(text.id, *text.args.toTypedArray())
    is DisplayText.Dynamic -> text.value
}

internal fun todayPage(vm: AppViewModel): String {
    val today = vm.today.value
    val context = vm.getApplication<android.app.Application>()
    val resolved = runBlocking(Dispatchers.IO) {
        val days = vm.repo.daysMerged(vm.activeStrapId)
        val drivers = today?.let { buildRecoveryDrivers(it, days) } ?: emptyList()

        // Recovery carry-over — mirrors TodayScreen.kt's lastScoredCharge: while tonight's recovery
        // hasn't been scored yet (right after the logical-day rollover, or on freshly-imported data),
        // show the most recent PRIOR scored night instead of a bare blank, the same "Last night ·
        // <date>" fallback every recovery-derived readout on the native screen carries. Without it the
        // whole recovery side reads as broken instead of just "not scored yet". Deliberately narrower
        // than the native screen in one respect: the calibration-progress display ("N of 4 nights") is
        // a separate feature and not replicated here, so a mid-calibration today is treated the same as
        // any other today with no recovery — [isCalibrating] is always false.
        val todayKey = today?.day ?: java.time.LocalDate.now().toString()
        val priorScored = lastScoredRecoveryDay(
            days = days,
            selectedDayKey = todayKey,
            isToday = true,
            todayScored = today?.recovery != null,
            isCalibrating = false,
            today = todayKey,
        )
        val recovery = today?.recovery ?: priorScored?.recovery
        val recoveryCarryCaption = if (today?.recovery == null && priorScored != null) {
            displayTextString(carriedCaption(priorScored.day, todayKey))
        } else {
            null
        }

        // Live strain blend — mirrors TodayScreen.kt's effectiveEffort: `today.strain` only refreshes
        // when the heavy daily pass runs, so early in the day it holds a stale 0.0 (or yesterday's
        // value) instead of what's actually accrued. Integrate today's raw HR the same way the native
        // live-Effort path does and take the max with the stored value (Effort must never visibly
        // drop). Simplification vs the native screen: always uses calendar midnight as the day
        // boundary, not the sleep-onset-aware DayCycleMode.SLEEP_ONSET boundary — replicating that would
        // need a third data source (onset markers) purely to decide "when did today start" for a
        // read-only snapshot page.
        val zone = java.time.ZoneId.systemDefault()
        val nowS = System.currentTimeMillis() / 1000
        val dayStartS = java.time.LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        val todayHr = runCatching { vm.repo.hrSamplesUnion(vm.activeStrapId, dayStartS, nowS) }
            .getOrDefault(emptyList())
        val profile = ProfileStore.from(context)
        val effMaxHR = profile.hrMaxOverride.takeIf { it > 0 }?.toDouble()
            ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age.toDouble()) else null
        val restingHr = today?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
        val liveStrain = StrainScorer.strain(
            hr = todayHr,
            maxHR = effMaxHR,
            restingHR = restingHr,
            method = NoopPrefs.effortMethod(context),
            sex = profile.sex,
        )
        val strain = StrainScorer.effectiveEffort(live = liveStrain, stored = today?.strain)

        TodayResolved(drivers, recovery, recoveryCarryCaption, strain)
    }
    val driversHtml = if (resolved.drivers.isNotEmpty()) {
        """
        <div class="section-label">What Shaped It</div>
        <div class="card">
          ${resolved.drivers.joinToString("\n") { driverRowHtml(it) }}
        </div>
        """.trimIndent()
    } else {
        ""
    }
    val body = """
        <div class="header"><h1>Today</h1></div>

        <div class="ring-section">
          ${recoveryRingSvg(resolved.recovery)}
          ${resolved.recoveryCarryCaption?.let { "<div class=\"ring-caption\">$it</div>" } ?: ""}
        </div>

        <div class="tile-grid">
          ${statTile("Strain", resolved.strain?.let { "%.1f".format(it) }, "effort")}
          ${statTile("Resting HR", today?.restingHr?.let { "$it bpm" })}
        </div>

        $driversHtml
    """.trimIndent()
    return pageShell(Tab.TODAY, "NOOP · Today", body)
}

internal fun workoutsPage(vm: AppViewModel): String {
    val running = vm.activeWorkout.value != null
    val statusClass = if (running) "status-card active" else "status-card"
    val statusText = if (running) "Workout in progress" else "No workout in progress"
    val buttons = WorkoutHttpServer.BUTTONS.joinToString("\n") { b ->
        "<a class=\"btn btn-start\" href=\"/start?type=${b.param}\">${b.label}</a>"
    }
    val nowS = System.currentTimeMillis() / 1000
    val recent = runBlocking(Dispatchers.IO) {
        vm.repo.workoutsUnion(vm.activeStrapId, nowS - WorkoutHttpServer.RECENT_WORKOUTS_WINDOW_S, nowS, limit = 10)
    }
    val body = """
        <div class="header"><h1>Workouts</h1></div>

        <div class="$statusClass"><span class="status-dot"></span>$statusText</div>

        <div class="section-label">Start a Workout</div>
        <div class="button-grid">
          $buttons
        </div>
        <a class="btn btn-stop" href="/stop">Stop</a>

        <div class="section-label" style="margin-top: var(--section-gap);">Recent Workouts</div>
        <div class="card">
          ${recentWorkoutsHtml(recent)}
        </div>
    """.trimIndent()
    return pageShell(Tab.WORKOUTS, "NOOP · Workouts", body)
}

internal fun sleepPage(vm: AppViewModel): String {
    val today = vm.today.value
    val session = runBlocking(Dispatchers.IO) { latestComputedSession(vm, today) }
    val realBar = session?.stagesJSON?.let { parsePersistedSegments(it) }?.let { realStageBarHtml(it) }
    val stageBarHtml = realBar ?: today?.let { fallbackStageBarHtml(it) } ?: "<p class=\"empty\">No sleep data yet.</p>"
    val score = today?.let { restScore(it) }
    val body = """
        <div class="header"><h1>Sleep</h1></div>

        <div class="ring-section">
          ${sleepRingSvg(score)}
        </div>

        <div class="section-label">Last Night</div>
        <div class="card">
          $stageBarHtml
          ${stageLegendHtml()}
        </div>

        <div class="section-label">Details</div>
        <div class="card">
          ${statRow("Total sleep", today?.totalSleepMin?.let { formatMinutes(it) })}
          ${statRow("Efficiency", today?.efficiency?.let { "${efficiencyPct(it).roundToInt()}%" })}
          ${statRow("Deep", today?.deepMin?.let { formatMinutes(it) }, valueColor = "var(--sleep-deep)")}
          ${statRow("REM", today?.remMin?.let { formatMinutes(it) }, valueColor = "var(--sleep-rem)")}
          ${statRow("Light", today?.lightMin?.let { formatMinutes(it) }, valueColor = "var(--sleep-light)")}
          ${statRow("Avg HRV", today?.avgHrv?.let { "${it.roundToInt()} ms" })}
        </div>
    """.trimIndent()
    return pageShell(Tab.SLEEP, "NOOP · Sleep", body)
}

internal fun healthPage(vm: AppViewModel): String {
    val today = vm.today.value
    val profile = ProfileStore.from(vm.getApplication<android.app.Application>())
    val (history, stepsReadings, kcalReadings) = runBlocking(Dispatchers.IO) {
        val h = vm.repo.daysMerged(vm.activeStrapId)
        val (steps, kcal) = resolveActivityReadings(vm)
        Triple(h, steps, kcal)
    }
    val fitnessAge = today?.let { buildFitnessAge(profile, it, history) }
    val rhrDays = history.sortedBy { it.day }.takeLast(7).count { it.restingHr != null }

    // Same freshest-day resolution the ring/tile values elsewhere on this page use: prefer today's own
    // row if the merged series has one, else the newest available (a stale-but-real number beats "—").
    val stepsToday = stepsReadings.firstOrNull { it.day == today?.day } ?: stepsReadings.lastOrNull()
    val kcalToday = kcalReadings.firstOrNull { it.day == today?.day } ?: kcalReadings.lastOrNull()

    val activity = """
        <div class="section-label">Activity</div>
        <div class="prominent-grid">
          ${prominentCard("Steps", stepsToday?.value?.roundToInt()?.toString(), null, stepsReadings.takeLast(14).map { it.value }, "var(--sleep-light)")}
          ${prominentCard("Active Calories", kcalToday?.value?.roundToInt()?.toString(), "kcal", kcalReadings.takeLast(14).map { it.value }, "var(--effort)")}
        </div>
    """.trimIndent()

    val vitals = """
        <div class="section-label">Vitals</div>
        <div class="tile-grid">
          ${vitalTile("Resting HR", today?.restingHr?.let { "$it bpm" }, sparklineValues(history) { it.restingHr?.toDouble() }, "var(--effort)")}
          ${vitalTile("HRV", today?.avgHrv?.let { "${it.roundToInt()} ms" }, sparklineValues(history) { it.avgHrv }, "var(--charge)")}
          ${vitalTile("SpO2", today?.spo2Pct?.let { "${"%.1f".format(it)}%" }, sparklineValues(history) { it.spo2Pct }, "var(--sleep-light)")}
          ${vitalTile("Skin Temp", today?.skinTempDevC?.let { "${"%+.1f".format(it)}°C" }, sparklineValues(history) { it.skinTempDevC }, "var(--critical)")}
          ${vitalTile("Resp Rate", today?.respRateBpm?.let { "${"%.1f".format(it)} br/min" }, sparklineValues(history) { it.respRateBpm }, "var(--sleep-rem)")}
          ${vitalTile("Avg SDNN", today?.avgSdnn?.let { "${it.roundToInt()} ms" }, sparklineValues(history) { it.avgSdnn }, "var(--charge)")}
        </div>
    """.trimIndent()

    val fitnessAgeHtml = if (fitnessAge != null) {
        """
        <div class="section-label">Fitness Age</div>
        <div class="card">
          <div class="fitness-age-hero">
            <span class="fitness-age-value">${fitnessAge.fitnessAge.roundToInt()}</span>
            <span class="fitness-age-unit">years (${fitnessAge.chronoAge.roundToInt()} chronologically)</span>
          </div>
          ${fitnessAgeDeltaHtml(fitnessAge)}
          <div class="fitness-age-caption">Based on $rhrDays of the last 7 nights${if (fitnessAge.lowerConfidence) " · lower confidence" else ""}</div>
        </div>
        """.trimIndent()
    } else {
        """
        <div class="section-label">Fitness Age</div>
        <div class="card"><p class="empty">Not enough data yet — needs your age, sex, and a few nights of resting HR (set in Settings).</p></div>
        """.trimIndent()
    }

    val body = """
        <div class="header"><h1>Health</h1></div>
        $activity
        $vitals
        $fitnessAgeHtml
    """.trimIndent()
    return pageShell(Tab.HEALTH, "NOOP · Health", body)
}

/**
 * Steps and Active Calories, resolved with the EXACT same disjoint-store precedence the native Today
 * Key-Metrics tile and Health detail screens use (`HealthVitalDetailLogic.kt`'s "steps_est"/"active_kcal"
 * branches) — not the raw `DailyMetric.steps` / `.activeKcalEst` columns alone, which miss a day entirely
 * for an imported-only (e.g. WHOOP 4.0 + Health Connect) user. Steps: on-device count
 * (`resolvedSeries("steps")`) > imported Health Connect/Apple Health > the on-device motion estimate
 * (`resolvedSeries("steps_est")`) via [mergeStepsReadings]. Calories: imported active energy > the
 * on-device HR estimate (`resolvedSeries("active_kcal")`) via [mergeReadings] — imported wins its day
 * first, matching the native precedence exactly.
 */
private suspend fun resolveActivityReadings(vm: AppViewModel): Pair<List<VitalReading>, List<VitalReading>> {
    val deviceId = vm.activeStrapId
    // "manual-http" (the opt-in /steps HTTP receiver) is appended LAST: the putIfAbsent loops below only
    // let it fill a day neither apple-health nor health-connect already has, never override a real sync.
    val imported = (vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
        vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31") +
        vm.repo.appleDaily("manual-http", "0000-01-01", "9999-12-31"))

    val realSteps = vm.repo.resolvedSeries("steps", "my-whoop", "0000-00-00", "9999-99-99", strapDeviceId = deviceId)
        .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
    val importedSteps = LinkedHashMap<String, VitalReading>()
    for (r in imported) {
        val s = r.steps
        if (s != null && s > 0) importedSteps.putIfAbsent(r.day, VitalReading(r.day, s.toDouble(), r.deviceId))
    }
    val estSteps = vm.repo.resolvedSeries("steps_est", "my-whoop", "0000-00-00", "9999-99-99", strapDeviceId = deviceId)
        .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
    val steps = mergeStepsReadings(realSteps, importedSteps, estSteps)

    val realKcal = vm.repo.resolvedSeries("active_kcal", "my-whoop", "0000-00-00", "9999-99-99", strapDeviceId = deviceId)
        .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
    val importedKcal = LinkedHashMap<String, VitalReading>()
    for (r in imported) {
        val k = r.activeKcal
        if (k != null && k > 0) importedKcal.putIfAbsent(r.day, VitalReading(r.day, k, r.deviceId))
    }
    val kcal = mergeReadings(importedKcal, realKcal)

    return steps to kcal
}

/** A "prominent" activity card: a bigger number than [vitalTile], plus a real sparkline. No goal /
 *  progress bar — no daily step or calorie target exists anywhere in NOOP (checked before building
 *  this), so a progress fraction here would be fabricated. */
private fun prominentCard(label: String, value: String?, unit: String?, sparkValues: List<Double>, color: String): String {
    val spark = sparklineSvg(sparkValues, color, width = 200, height = 36)
    val unitHtml = unit?.let { " <span class=\"prominent-unit\">$it</span>" }.orEmpty()
    return """
    <div class="prominent-card">
      <div class="prominent-label">$label</div>
      <div class="prominent-value">${value ?: "—"}$unitHtml</div>
      $spark
    </div>
    """.trimIndent()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Shell + nav
// ─────────────────────────────────────────────────────────────────────────────────────────────

private fun pageShell(active: Tab, title: String, bodyHtml: String): String = """
    <!doctype html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>$title</title>
      <style>${styles()}</style>
    </head>
    <body>
      <div class="screen">
        $bodyHtml
      </div>
      ${navBarHtml(active)}
    </body>
    </html>
""".trimIndent()

private fun navBarHtml(active: Tab): String {
    val items = Tab.entries.joinToString("\n") { tab ->
        val activeClass = if (tab == active) " active" else ""
        "<a class=\"tab-item$activeClass\" href=\"${tab.route}\"><span class=\"tab-dot\"></span>${tab.label}</a>"
    }
    return "<nav class=\"tab-bar\">$items</nav>"
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Recovery / Sleep hero rings (inline SVG)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Geometry for the app's brand-glyph ring: an OPEN arc covering 288 of 360 degrees (not a full
 *  circle), matching [com.noop.ui.RecoveryRing]'s `spanDeg = 288f`. Returns (radius, full
 *  circumference, visible-arc length). */
private fun ringGeometry(size: Int, stroke: Int): Triple<Double, Double, Double> {
    val r = (size - stroke) / 2.0
    val circumference = 2 * Math.PI * r
    val trackLen = circumference * (288.0 / 360.0)
    return Triple(r, circumference, trackLen)
}

/** The Recovery hero ring: 5-stop gradient sweep over the exact `recovery000..100` ramp, matching
 *  [com.noop.ui.RecoveryRing]. Rotating both circles -90deg moves the SVG circle's default start
 *  point (3 o'clock) to 12 o'clock, matching `startDeg = -90f`; the dasharray then draws clockwise
 *  for 288 degrees before the (permanently empty) gap, exactly mirroring the native gauge. */
internal fun recoveryRingSvg(score: Double?, size: Int = 220, stroke: Int = 16): String {
    val (r, circumference, trackLen) = ringGeometry(size, stroke)
    val gapLen = circumference - trackLen
    val c = size / 2.0
    val pct = ((score ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
    val valueLen = trackLen * pct
    val stateWord = score?.let { recoveryStateWord(it) } ?: "—"
    val scoreText = score?.roundToInt()?.toString() ?: "—"
    return """
    <div class="ring-wrap" style="width:${size}px;height:${size}px;">
      <svg width="$size" height="$size" viewBox="0 0 $size $size">
        <defs>
          <linearGradient id="recoveryGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="var(--rec-0)"/>
            <stop offset="30%" stop-color="var(--rec-30)"/>
            <stop offset="55%" stop-color="var(--rec-55)"/>
            <stop offset="78%" stop-color="var(--rec-78)"/>
            <stop offset="100%" stop-color="var(--rec-100)"/>
          </linearGradient>
        </defs>
        <circle cx="$c" cy="$c" r="${"%.2f".format(r)}" fill="none" stroke="var(--hairline)" stroke-width="$stroke"
                stroke-linecap="round" stroke-dasharray="${"%.2f".format(trackLen)} ${"%.2f".format(gapLen)}"
                transform="rotate(-90 $c $c)"/>
        <circle class="ring-value" cx="$c" cy="$c" r="${"%.2f".format(r)}" fill="none" stroke="url(#recoveryGrad)" stroke-width="$stroke"
                stroke-linecap="round"
                stroke-dasharray="${"%.2f".format(valueLen)} ${"%.2f".format(circumference - valueLen)}"
                transform="rotate(-90 $c $c)"/>
      </svg>
      <div class="ring-center">
        <div class="ring-value-text">$scoreText<span class="ring-pct">%</span></div>
        <div class="ring-state">$stateWord</div>
      </div>
    </div>
    """.trimIndent()
}

/** The Sleep hero ring — same open-arc geometry, a single tint (the sleep-light blue) rather than the
 *  Recovery gradient, mirroring [com.noop.ui.SleepHeroVessel]'s single-tint fill. [score] is the Rest
 *  composite via [restScore] (real-app score), not raw efficiency. */
internal fun sleepRingSvg(score: Double?, size: Int = 200, stroke: Int = 14): String {
    val (r, circumference, trackLen) = ringGeometry(size, stroke)
    val gapLen = circumference - trackLen
    val c = size / 2.0
    val pct = ((score ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
    val valueLen = trackLen * pct
    val scoreText = score?.roundToInt()?.toString() ?: "—"
    return """
    <div class="ring-wrap" style="width:${size}px;height:${size}px;">
      <svg width="$size" height="$size" viewBox="0 0 $size $size">
        <circle cx="$c" cy="$c" r="${"%.2f".format(r)}" fill="none" stroke="var(--hairline)" stroke-width="$stroke"
                stroke-linecap="round" stroke-dasharray="${"%.2f".format(trackLen)} ${"%.2f".format(gapLen)}"
                transform="rotate(-90 $c $c)"/>
        <circle class="ring-value" cx="$c" cy="$c" r="${"%.2f".format(r)}" fill="none" stroke="var(--sleep-light)" stroke-width="$stroke"
                stroke-linecap="round"
                stroke-dasharray="${"%.2f".format(valueLen)} ${"%.2f".format(circumference - valueLen)}"
                transform="rotate(-90 $c $c)"/>
      </svg>
      <div class="ring-center">
        <div class="ring-value-text">$scoreText<span class="ring-pct">%</span></div>
        <div class="ring-state">Sleep Performance</div>
      </div>
    </div>
    """.trimIndent()
}

/** Mirrors [com.noop.ui.Theme]'s `Palette.recoveryState` thresholds exactly (spec Section 9.3). */
private fun recoveryStateWord(score: Double): String = when {
    score < 25 -> "DEPLETED"
    score < 50 -> "LOW"
    score < 70 -> "MODERATE"
    score < 88 -> "PRIMED"
    else -> "PEAK"
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Recovery Drivers ("what shaped it")
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Real Recovery Drivers, not an approximation: folds [history] into rolling HRV/RHR/resp baselines
 *  via the SAME pure [Baselines.rollingMeanSD] the app itself uses, then calls the SAME
 *  [RecoveryDrivers.chargeDrivers] the Today screen calls. Returns an empty list (never a fabricated
 *  row) exactly when the real function would too: missing tonight's HRV/RHR, or an unusable
 *  (calibrating) HRV baseline. */
private fun buildRecoveryDrivers(today: DailyMetric, history: List<DailyMetric>): List<ChargeDriver> {
    val hrv = today.avgHrv ?: return emptyList()
    val rhr = today.restingHr?.toDouble() ?: return emptyList()
    val sorted = history.sortedBy { it.day }
    // Baseline history is PRIOR nights only — tonight itself must not skew its own comparison baseline.
    val prior = if (sorted.isNotEmpty() && sorted.last().day == today.day) sorted.dropLast(1) else sorted
    val hrvBaseline = Baselines.rollingMeanSD(prior.map { it.avgHrv }, Baselines.hrvCfg)
    val rhrBaseline = Baselines.rollingMeanSD(prior.map { it.restingHr?.toDouble() }, Baselines.restingHRCfg)
    val respBaseline = Baselines.rollingMeanSD(prior.map { it.respRateBpm }, Baselines.respCfg)
    val sleepPerf = restScore(today)?.let { it / 100.0 }
    return RecoveryDrivers.chargeDrivers(
        hrv = hrv, rhr = rhr, resp = today.respRateBpm,
        hrvBaseline = hrvBaseline, rhrBaseline = rhrBaseline, respBaseline = respBaseline,
        sleepPerf = sleepPerf, skinTempDev = today.skinTempDevC,
    )
}

private fun driverLabelText(label: ChargeDriverLabel): String = when (label) {
    ChargeDriverLabel.HEART_RATE_VARIABILITY -> "Heart Rate Variability"
    ChargeDriverLabel.RESTING_HEART_RATE -> "Resting Heart Rate"
    ChargeDriverLabel.SLEEP_QUALITY -> "Sleep Quality"
    ChargeDriverLabel.RESPIRATORY_RATE -> "Respiratory Rate"
    ChargeDriverLabel.SKIN_TEMPERATURE -> "Skin Temperature"
}

private fun driverUnitSuffix(unit: ChargeDriverUnit): String = when (unit) {
    ChargeDriverUnit.MILLISECONDS -> "ms"
    ChargeDriverUnit.BEATS_PER_MINUTE -> "bpm"
    ChargeDriverUnit.PERCENT -> "%"
    ChargeDriverUnit.BREATHS_PER_MINUTE -> "br/min"
    ChargeDriverUnit.CELSIUS_DEVIATION -> "°C"
}

private fun driverRowHtml(d: ChargeDriver): String {
    val chipClass = when {
        d.deltaPoints > 0 -> "positive"
        d.deltaPoints < 0 -> "negative"
        else -> "neutral"
    }
    val sign = if (d.deltaPoints > 0) "+" else ""
    val unit = driverUnitSuffix(d.unit)
    val baselineText = d.baseline?.let { " (baseline ${"%.1f".format(it)}$unit)" } ?: ""
    return """
    <div class="driver-row">
      <div>
        <div class="driver-label">${driverLabelText(d.label)}</div>
        <div class="driver-detail">${"%.1f".format(d.value)}$unit$baselineText</div>
      </div>
      <div class="driver-chip $chipClass">$sign${d.deltaPoints}</div>
    </div>
    """.trimIndent()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Sleep: real hypnogram + fallback
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** The Rest composite via the SAME [RestScorer.restFromDaily] `SleepModelLogic`'s `performance` metric
 *  prefers, falling back to raw efficiency (normalized 0..1 vs 0..100, same rule as the real Sleep
 *  screen) only when the composite itself can't compute. */
private fun restScore(today: DailyMetric): Double? =
    RestScorer.restFromDaily(today) ?: today.efficiency?.let { efficiencyPct(it) }

/** The most recent ON-DEVICE staged sleep session matching [today]'s date, so its real `stagesJSON`
 *  can be parsed for the hypnogram — the computed union (not imported) since imported sessions
 *  rarely carry a real per-epoch stage array. Falls back to the newest session in the window if none
 *  matches the exact day (still real data, just not guaranteed to be "last night" if today's hasn't
 *  landed yet). */
private suspend fun latestComputedSession(vm: AppViewModel, today: DailyMetric?): SleepSession? {
    if (today == null) return null
    val nowS = System.currentTimeMillis() / 1000
    val sessions = vm.repo.computedSleepSessionsUnion(vm.activeStrapId, nowS - 3L * 86_400, nowS, limit = 5)
    return sessions.firstOrNull { AnalyticsEngine.dayString(it.endTs) == today.day }
        ?: sessions.maxByOrNull { it.endTs }
}

/** A proportional bar built from the REAL per-epoch segments (via `com.noop.ui.parsePersistedSegments`,
 *  module-internal, not private — genuinely reachable from here), in chronological order. Null when
 *  the session has no usable segments (e.g. an empty/corrupt stagesJSON), so the caller can fall back. */
private fun realStageBarHtml(segments: List<PersistedSegment>): String? {
    if (segments.isEmpty()) return null
    val startMin = segments.minOf { it.start }
    val total = segments.maxOf { it.end } - startMin
    if (total <= 0) return null
    val bars = segments.joinToString("") { seg ->
        val widthPct = (seg.end - seg.start) * 100.0 / total
        val stageKey = if (seg.stage.lowercase() in listOf("wake", "awake")) "awake" else seg.stage.lowercase()
        "<span class=\"stage-seg stage-$stageKey\" style=\"width:${"%.3f".format(widthPct)}%;\"></span>"
    }
    return "<div class=\"stage-bar\">$bars</div>"
}

/** Only used when [realStageBarHtml] has nothing to work with (no computed session / no stagesJSON,
 *  typically an imported-only night) — a proportional Deep/REM/Light bar from the nightly totals
 *  already on [DailyMetric]. No "Awake" segment: DailyMetric has no awake-minutes field to draw from. */
private fun fallbackStageBarHtml(today: DailyMetric): String {
    val deep = today.deepMin ?: 0.0
    val rem = today.remMin ?: 0.0
    val light = today.lightMin ?: 0.0
    val total = deep + rem + light
    if (total <= 0) return "<p class=\"empty\">No sleep data yet.</p>"
    fun seg(stage: String, min: Double) =
        if (min <= 0) "" else "<span class=\"stage-seg stage-$stage\" style=\"width:${"%.3f".format(min * 100 / total)}%;\"></span>"
    return "<div class=\"stage-bar\">${seg("light", light)}${seg("deep", deep)}${seg("rem", rem)}</div>"
}

private fun stageLegendHtml(): String = """
    <div class="stage-legend">
      <span class="stage-legend-item"><span class="stage-legend-dot stage-awake"></span>Awake</span>
      <span class="stage-legend-item"><span class="stage-legend-dot stage-light"></span>Light</span>
      <span class="stage-legend-item"><span class="stage-legend-dot stage-deep"></span>Deep</span>
      <span class="stage-legend-item"><span class="stage-legend-dot stage-rem"></span>REM</span>
    </div>
""".trimIndent()

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Health: vitals + sparklines + Fitness Age
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Last [days] nights' values for one [DailyMetric] field, oldest first — the same shape a
 *  trend/sparkline needs, fetched via the same [com.noop.data.WhoopRepository.daysMerged] call
 *  already made once per page (not a new query per tile). */
private fun sparklineValues(history: List<DailyMetric>, days: Int = 14, selector: (DailyMetric) -> Double?): List<Double> =
    history.sortedBy { it.day }.takeLast(days).mapNotNull(selector)

private fun sparklineSvg(values: List<Double>, color: String, width: Int = 120, height: Int = 28): String {
    if (values.size < 2) return ""
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0001 } ?: 1.0
    val stepX = width.toDouble() / (values.size - 1)
    val points = values.mapIndexed { i, v ->
        val x = i * stepX
        val y = height - ((v - min) / range) * height
        "%.1f,%.1f".format(x, y)
    }.joinToString(" ")
    return """<svg class="sparkline" viewBox="0 0 $width $height" preserveAspectRatio="none"><polyline points="$points" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
}

private fun vitalTile(label: String, value: String?, sparkValues: List<Double>, color: String): String {
    val spark = sparklineSvg(sparkValues, color)
    return """
    <div class="tile vital-tile">
      <div class="value">${value ?: "—"}</div>
      <div class="label">$label</div>
      $spark
    </div>
    """.trimIndent()
}

/** Real Fitness Age via [FitnessAgeEngine.compute] — age/sex from the SAME [ProfileStore] Settings
 *  writes to, resting HR from tonight (falling back to the last-7-night mean, since `compute` requires
 *  a positive value), and the physical-activity index folded from [history]'s strain the SAME way
 *  [FitnessAgeEngine.physicalActivityIndexFromStrain] expects. Returns null exactly when `compute`
 *  would (missing age or resting HR) — never a fabricated number. */
private fun buildFitnessAge(profile: ProfileStore, today: DailyMetric, history: List<DailyMetric>): FitnessAgeResult? {
    val age = profile.age.toDouble()
    val last7 = history.sortedBy { it.day }.takeLast(7)
    val restingHR = today.restingHr?.toDouble()
        ?: last7.mapNotNull { it.restingHr?.toDouble() }.average().takeIf { !it.isNaN() }
        ?: return null
    val activeDays = last7.count { (it.strain ?: 0.0) > 0.0 }
    val meanActiveStrain = last7.mapNotNull { it.strain }.filter { it > 0.0 }.average().takeIf { !it.isNaN() } ?: 0.0
    val paIndex = FitnessAgeEngine.physicalActivityIndexFromStrain(activeDays, meanActiveStrain)
    return FitnessAgeEngine.compute(age = age, sex = profile.sex, restingHR = restingHR, paIndex = paIndex)
}

private fun fitnessAgeDeltaHtml(result: FitnessAgeResult): String {
    val delta = result.deltaYears
    val cls = if (delta >= 0) "positive" else "negative"
    val word = if (delta >= 0) "younger" else "older"
    return "<div class=\"fitness-age-delta $cls\">${"%.1f".format(abs(delta))} years $word than your age</div>"
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Small shared render helpers (tiles, rows, workout list)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** DailyMetric.efficiency isn't consistently one scale: the on-device scorer stores a raw 0..1
 *  fraction (asleep-seconds / in-bed-seconds), but some imported sources write it already as 0..100.
 *  Mirrors `SleepModelLogic.kt`'s `efficiency` metric transform exactly, so this always matches the
 *  real Sleep screen instead of occasionally showing "1%" for a 92%-efficient night. */
private fun efficiencyPct(raw: Double): Double = if (raw <= 1.0) raw * 100.0 else raw

/** A label/value row inside a card. [valueColor] lets a row borrow one of the sleep-stage tokens
 *  (Deep/REM/Light) instead of the default text color. */
private fun statRow(label: String, value: String?, valueColor: String? = null): String {
    val style = valueColor?.let { " style=\"color:$it;\"" }.orEmpty()
    return "<div class=\"stat-row\"><span>$label</span><strong$style>${value ?: "—"}</strong></div>"
}

/** A Today-screen-style metric tile: big colored number over a small caption. [tone] selects one of
 *  the app's named data-color "worlds" (`charge` = recovery green, `effort` = strain blue); omitted
 *  for a plain readout like Resting HR, which isn't one of the app's branded metrics. */
private fun statTile(label: String, value: String?, tone: String? = null): String {
    val toneClass = tone?.let { " tile-$it" }.orEmpty()
    return """
    <div class="tile$toneClass">
      <div class="value">${value ?: "—"}</div>
      <div class="label">$label</div>
    </div>
    """.trimIndent()
}

private fun recentWorkoutsHtml(rows: List<WorkoutRow>): String {
    if (rows.isEmpty()) return "<p class=\"empty\">No recent workouts.</p>"
    return rows.joinToString("\n") { w ->
        val startedAt = Instant.ofEpochSecond(w.startTs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        val duration = w.durationS?.let { formatMinutes(it / 60.0) }
        val avgHr = w.avgHr?.let { "$it bpm avg" }
        val detail = listOfNotNull(duration, avgHr).joinToString(" · ")
        """
        <div class="workout-row">
          <div class="sport">${w.sport}</div>
          <div class="meta">$startedAt &middot; ${detail.ifEmpty { "—" }}</div>
        </div>
        """.trimIndent()
    }
}

/** "Xh Ym" (or just "Ym" under an hour) from a minute count — shared by the sleep block and the
 *  recent-workouts duration. */
private fun formatMinutes(totalMin: Double): String {
    val total = totalMin.roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
