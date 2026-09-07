package com.noop.server

import com.noop.analytics.WorkoutSport
import com.noop.ui.ActiveAppViewModel
import com.noop.ui.AppViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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
        runBlocking(Dispatchers.Main.immediate) { vm.startWorkout(sport, gpsEnabled = false) }
        return redirectTo("/workouts")
    }

    private fun handleStop(): Response {
        val vm = ActiveAppViewModel.current ?: return unavailable()
        runBlocking(Dispatchers.Main.immediate) { vm.endWorkout() }
        return redirectTo("/workouts")
    }

    private fun unavailable(): Response =
        textResponse(Response.Status.SERVICE_UNAVAILABLE, "NOOP isn't open on the phone — open the app once, then try again.")

    private fun redirectTo(path: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", path)
        }

    private fun textResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    private fun htmlResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html", body)

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
