package com.noop.server

import com.noop.analytics.WorkoutSport
import com.noop.ui.ActiveAppViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Minimal local HTTP control surface for starting/stopping a manual workout from another device on
 * the same WiFi network (e.g. triggering a session from an iPhone's Safari browser while the strap
 * is paired to this Android phone). Phase 2 of the manual-workout-trigger feature.
 *
 * Deliberately no auth and no HTTPS: it is meant for same-WiFi personal use, not exposure to an
 * untrusted network. Hardening (auth token, binding to a single interface) is a follow-up, not done
 * here.
 *
 * Routing is delegated to [ActiveAppViewModel] rather than to the process-owned repository/BLE client
 * directly, because starting/ending a workout is stateful UI-session logic (the in-flight
 * [com.noop.ui.AppViewModel]'s `_activeWorkout`), not a simple data write — see [ActiveAppViewModel]'s
 * doc for why that means this control page only works while the app has been opened at least once in
 * the current process lifetime.
 */
class WorkoutHttpServer(port: Int = PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/" -> htmlResponse(indexHtml())
            "/start" -> handleStart(session)
            "/stop" -> handleStop()
            else -> textResponse(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun handleStart(session: IHTTPSession): Response {
        val vm = ActiveAppViewModel.current
            ?: return textResponse(Response.Status.SERVICE_UNAVAILABLE, "NOOP isn't open on the phone — open the app once, then try again.")
        val param = session.parms["type"].orEmpty()
        val button = BUTTONS.firstOrNull { it.param.equals(param, ignoreCase = true) }
            ?: return textResponse(Response.Status.BAD_REQUEST, "Unknown workout type: $param")
        val sport = WorkoutSport.all.firstOrNull { it.name == button.catalogName } ?: WorkoutSport.default
        // startWorkout/endWorkout mutate ViewModel StateFlows that the rest of the app assumes change
        // on the main thread (mirroring every other call site, which is Compose UI). NanoHTTPD calls
        // serve() on its own per-request worker thread, so hop over rather than mutate cross-thread;
        // blocking this worker thread on Main is safe since Main isn't the thread making this call.
        runBlocking(Dispatchers.Main.immediate) { vm.startWorkout(sport, gpsEnabled = false) }
        return redirectHome()
    }

    private fun handleStop(): Response {
        val vm = ActiveAppViewModel.current
            ?: return textResponse(Response.Status.SERVICE_UNAVAILABLE, "NOOP isn't open on the phone — open the app once, then try again.")
        runBlocking(Dispatchers.Main.immediate) { vm.endWorkout() }
        return redirectHome()
    }

    private fun redirectHome(): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", "/")
        }

    private fun textResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    private fun htmlResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html", body)

    private fun indexHtml(): String {
        val running = ActiveAppViewModel.current?.activeWorkout?.value != null
        val buttons = BUTTONS.joinToString("\n") { b ->
            "<a class=\"btn\" href=\"/start?type=${b.param}\">${b.label}</a>"
        }
        val status = if (running) "A workout is in progress." else "No workout in progress."
        return """
            <!doctype html>
            <html>
            <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>NOOP workout control</title></head>
            <body style="font-family: sans-serif; padding: 24px;">
              <h2>NOOP workout control</h2>
              <p>$status</p>
              <div style="display:flex; flex-direction:column; gap:12px; max-width:320px;">
                $buttons
                <a class="btn" href="/stop" style="background:#c0392b;">Stop</a>
              </div>
              <style>.btn { display:block; padding:16px; text-align:center; background:#2c3e50; color:white; text-decoration:none; border-radius:8px; font-size:18px; }</style>
            </body>
            </html>
        """.trimIndent()
    }

    private data class Button(val param: String, val label: String, val catalogName: String)

    companion object {
        const val PORT = 8080

        // param -> button label -> WorkoutSport.all catalog name (see com.noop.ingest.ExerciseTypes).
        private val BUTTONS = listOf(
            Button("tennis", "Start Tennis", "Tennis"),
            Button("run", "Start Run", "Running"),
            Button("walk", "Start Walk", "Walking"),
            Button("basketball", "Start Basketball", "Basketball"),
        )
    }
}
