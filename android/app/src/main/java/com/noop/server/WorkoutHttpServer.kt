package com.noop.server

import com.noop.analytics.WorkoutSport
import com.noop.ble.PuffinExperiment
import com.noop.data.AppleDaily
import com.noop.ui.ActiveAppViewModel
import com.noop.ui.AppViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Local HTTP surface for the NOOP companion web pages: a 4-tab app (Today/Workouts/Sleep/Health,
 * see `WebDashboardPages.kt`) plus the manual workout start/stop actions from Phase 2, reachable
 * from another device on the same WiFi network (e.g. an iPhone's Safari browser).
 *
 * Deliberately no auth and no HTTPS: it is meant for same-WiFi personal use, not exposure to an
 * untrusted network. Hardening (auth token, binding to a single interface) is a follow-up, not done
 * here.
 *
 * This class owns routing and the two write actions only; all HTML comes from `WebDashboardPages.kt`.
 * Routing is delegated to [ActiveAppViewModel] rather than to the process-owned repository/BLE client
 * directly, because starting/ending a workout is stateful UI-session logic (the in-flight
 * [AppViewModel]'s `_activeWorkout`), not a simple data write — see [ActiveAppViewModel]'s doc for why
 * that means these pages only work while the app has been opened at least once in the current process
 * lifetime.
 */
class WorkoutHttpServer(port: Int = PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        // Old bookmarks/links from Phase 2-3 keep working, redirected to the new Today tab.
        "/", "/dashboard" -> redirectTo("/today")
        "/today" -> renderPage(::todayPage)
        "/workouts" -> renderPage(::workoutsPage)
        "/sleep" -> renderPage(::sleepPage)
        "/health" -> renderPage(::healthPage)
        "/start" -> handleStart(session)
        "/stop" -> handleStop()
        "/steps" -> handleSteps(session)
        else -> textResponse(Response.Status.NOT_FOUND, "Not found")
    }

    private fun renderPage(render: (AppViewModel) -> String): Response {
        val vm = ActiveAppViewModel.current
            ?: return unavailable()
        return htmlResponse(render(vm))
    }

    private fun handleStart(session: IHTTPSession): Response {
        val vm = ActiveAppViewModel.current ?: return unavailable()
        val param = session.parms["type"].orEmpty()
        val button = BUTTONS.firstOrNull { it.param.equals(param, ignoreCase = true) }
            ?: return textResponse(Response.Status.BAD_REQUEST, "Unknown workout type: $param")
        // No silent fallback to WorkoutSport.default: a typo'd catalogName (ours or a future button's)
        // must surface as an error, never quietly log the workout under "Other".
        val sport = WorkoutSport.all.firstOrNull { it.name == button.catalogName }
            ?: return textResponse(Response.Status.BAD_REQUEST, "Sport '${button.catalogName}' not found in catalog")
        // startWorkout/endWorkout mutate ViewModel StateFlows that the rest of the app assumes change
        // on the main thread (mirroring every other call site, which is Compose UI). NanoHTTPD calls
        // serve() on its own per-request worker thread, so hop over rather than mutate cross-thread;
        // blocking this worker thread on Main is safe since Main isn't the thread making this call.
        // Caught explicitly (not just left to propagate): an uncaught throw here would otherwise surface
        // to the tapping browser as a dropped connection with no explanation at all.
        try {
            runBlocking(Dispatchers.Main.immediate) { vm.startWorkout(sport, gpsEnabled = false) }
        } catch (e: Exception) {
            return textResponse(Response.Status.INTERNAL_ERROR, "startWorkout() threw: ${e.message}")
        }
        return redirectTo("/workouts")
    }

    private fun handleStop(): Response {
        val vm = ActiveAppViewModel.current ?: return unavailable()
        try {
            runBlocking(Dispatchers.Main.immediate) { vm.endWorkout() }
        } catch (e: Exception) {
            return textResponse(Response.Status.INTERNAL_ERROR, "endWorkout() threw: ${e.message}")
        }
        return redirectTo("/workouts")
    }

    /**
     * Manual step-count receiver (opt-in, default OFF — see [PuffinExperiment.manualHttpSteps]): lets a
     * same-WiFi device (e.g. an iOS Shortcut reading Apple Health) push a day's step count into NOOP
     * directly, a workaround for a broken Health Connect sync rather than a replacement for it. Writes
     * under deviceId "manual-http" via the SAME [AppleDaily] table/upsert every importer uses; the
     * "manual-http" fallback tier ([com.noop.ui.stepsForDay] and its siblings) only fills a day neither
     * apple-health nor health-connect already covers, so a real sync always wins over a manual entry for
     * the same day. `GET /steps?date=YYYY-MM-DD&count=N&token=<secret>`.
     *
     * The token check is deliberately NOT full authentication — this route sits on the same plain-HTTP,
     * no-TLS, same-WiFi-trust surface as every other route here (see the class doc). It exists only to
     * close the gap that matters for THIS route specifically: unlike /start or /stop (a control signal
     * into logic the user can immediately see and undo), this writes directly into permanent, precedence-
     * winning health data — a stray device on the same WiFi should not be able to silently corrupt it
     * with zero friction just because it can reach the port.
     */
    private fun handleSteps(session: IHTTPSession): Response {
        val vm = ActiveAppViewModel.current ?: return unavailable()
        val puffin = PuffinExperiment.from(vm.getApplication())
        if (!puffin.manualHttpSteps) {
            return textResponse(
                Response.Status.FORBIDDEN,
                "Manual steps via HTTP is not enabled. Turn it on in Settings first.",
            )
        }
        val token = session.parms["token"].orEmpty()
        if (token.isEmpty() || token != puffin.manualHttpStepsToken) {
            return textResponse(Response.Status.FORBIDDEN, "Invalid or missing token.")
        }
        val dateParam = session.parms["date"] ?: LocalDate.now().toString()
        val date = try {
            LocalDate.parse(dateParam).toString()
        } catch (e: DateTimeParseException) {
            return textResponse(Response.Status.BAD_REQUEST, "Invalid 'date' — expected YYYY-MM-DD, got '$dateParam'.")
        }
        val count = session.parms["count"]?.toIntOrNull()
            ?: return textResponse(Response.Status.BAD_REQUEST, "Missing or invalid 'count' — expected a whole number.")
        // Sanity bound, not a real physiological ceiling: catches a fat-fingered extra digit or a unit
        // mix-up (e.g. meters instead of steps) without pretending to validate a real day's plausibility.
        if (count !in 0..100_000) {
            return textResponse(Response.Status.BAD_REQUEST, "'count' out of range (0-100,000): $count.")
        }
        try {
            runBlocking(Dispatchers.IO) {
                vm.repo.upsertAppleDaily(listOf(AppleDaily(deviceId = "manual-http", day = date, steps = count)))
            }
        } catch (e: Exception) {
            return textResponse(Response.Status.INTERNAL_ERROR, "Write failed: ${e.message}")
        }
        return textResponse(Response.Status.OK, "Recorded $count steps for $date.")
    }

    private fun unavailable(): Response =
        textResponse(Response.Status.SERVICE_UNAVAILABLE, "NOOP isn't open on the phone — open the app once, then try again.")

    // Response.Status.REDIRECT is HTTP 301 (Moved Permanently) — a status
    // browsers are permitted to cache HEURISTICALLY AND INDEFINITELY with no cache-control header at
    // all, unlike 302/303/307. A browser that ever loaded /start?type=X once can silently satisfy every
    // later tap of that same link entirely from its own cache — no request ever reaches the phone again,
    // which reads as "tapping the button does nothing" with no error and no network activity, exactly
    // the symptom this file's own debug logging would show as a MISSING handleStart log line. Every
    // response from this state-changing surface (start/stop AND the pages that show their result) must
    // therefore be explicitly uncacheable: REDIRECT_SEE_OTHER (303) is never cached by default even
    // without headers, and the explicit no-store/no-cache headers below make that unambiguous for every
    // browser and any intermediate proxy, belt-and-braces.
    private fun noCache(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        addHeader("Pragma", "no-cache")
    }

    private fun redirectTo(path: String): Response =
        noCache(newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")).apply {
            addHeader("Location", path)
        }

    private fun textResponse(status: Response.Status, body: String): Response =
        noCache(newFixedLengthResponse(status, "text/plain", body))

    private fun htmlResponse(body: String): Response =
        noCache(newFixedLengthResponse(Response.Status.OK, "text/html", body))

    /** One "Start X" button on the Workouts page. [catalogName] must be the EXACT
     *  [com.noop.ingest.ExerciseTypes] name — e.g. "Strength", not "Strength Training" — the button
     *  [label] can read however you like; only the catalog lookup key has to match. */
    internal data class Button(val param: String, val label: String, val catalogName: String)

    companion object {
        const val PORT = 8080

        /** How far back the Workouts page's history list looks. */
        internal const val RECENT_WORKOUTS_WINDOW_S = 14L * 86_400

        internal val BUTTONS = listOf(
            Button("tennis", "Start Tennis", "Tennis"),
            Button("run", "Start Run", "Running"),
            Button("walk", "Start Walk", "Walking"),
            Button("basketball", "Start Basketball", "Basketball"),
            Button("strength", "Start Strength Training", "Strength"),
            Button("golf", "Start Golf", "Golf"),
            Button("pickleball", "Start Pickleball", "Pickleball"),
        )
    }
}
