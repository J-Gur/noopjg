package com.noop.protocol

// Whoop4RawImu.kt — decoder for the WHOOP 4.0 realtime raw 6-axis IMU stream: REALTIME_RAW_DATA
// (packet type 43, internally "R10/R11"), the 1917-byte "imu" payload variant. See
// docs/BLE_REVERSE_ENGINEERING.md §4 for the byte layout, scales, and on-device validation evidence
// this decoder applies — no new protocol claims are made here, only the already-verified facts.
//
// Unlike the WHOOP 5/MG's offload burst (Whoop5RawImu.kt, a different 1244-byte buffer with its own
// on-strap timestamp), this stream is a LIVE flood with no documented frame timestamp suitable for
// strap-clock reconciliation. Callers timestamp frames at phone receive time instead, which is
// sufficient for a short bounded live capture (tracked-workout duration) but NOT for offline history
// repair the way the 5/MG path supports.
//
// Deliberately NOT wired into any production score. docs/RAW_DATA_CAPTURE.md states the project's own
// stance plainly: raw IMU capture is a research/estimate facility, never a production health or
// activity input. Anything built on this decoder (see ImuFeatureExtractor.stepsInWindow and its
// Experimental gate in PuffinExperiment) must keep that same posture — labeled as an estimate, never
// silently overriding an honest "no data" with an unvalidated number.

/** One decoded WHOOP 4.0 realtime IMU buffer: a ~1-second window of 6-axis samples, receive-time
 *  stamped (see file header for why — this stream carries no usable on-strap frame timestamp). */
data class Whoop4ImuFrame(
    val receivedAtMs: Long,
    val sampleRateHz: Int,
    val samples: List<RawImuSample>,
)

object Whoop4RawImu {

    const val bufferLength = 1917
    const val sampleCount = 100
    const val accelScale = 1.0 / 4096.0        // g per LSB — same scale as the 5/MG (Whoop5RawImu), hardware-verified separately for the 4.0 in BLE_REVERSE_ENGINEERING.md §4.
    const val gyroScale = 2000.0 / 32768.0     // deg/s per LSB (±2000 dps)

    // FRAME-absolute offsets, per BLE_REVERSE_ENGINEERING.md §4 (hardware-verified there via
    // sphere-fit accel magnitude and controlled 720° rotation tests).
    private const val axOff = 89
    private const val ayOff = 289
    private const val azOff = 489
    private const val gxOff = 692
    private const val gyOff = 892
    private const val gzOff = 1092

    /** Decode a WHOOP 4.0 realtime raw-IMU buffer, or null if it isn't one. Gated on exact length
     *  only (1917), matching how the two type-43 variants are distinguished — the "optical" variant is
     *  1921 bytes, so the two can never be confused. [receivedAtMs] should be the phone's own receive
     *  time for the frame (see file header). */
    fun decode(f: ByteArray, receivedAtMs: Long): Whoop4ImuFrame? {
        if (f.size != bufferLength) return null
        if (gzOff + 2 * sampleCount > f.size) return null
        val samples = ArrayList<RawImuSample>(sampleCount)
        for (i in 0 until sampleCount) {
            val o = 2 * i
            samples.add(
                RawImuSample(
                    ax = i16(f, axOff + o) * accelScale,
                    ay = i16(f, ayOff + o) * accelScale,
                    az = i16(f, azOff + o) * accelScale,
                    gx = i16(f, gxOff + o) * gyroScale,
                    gy = i16(f, gyOff + o) * gyroScale,
                    gz = i16(f, gzOff + o) * gyroScale,
                ),
            )
        }
        return Whoop4ImuFrame(receivedAtMs = receivedAtMs, sampleRateHz = sampleCount, samples = samples)
    }

    // Little-endian readers (frame-absolute) — byte-identical helpers to Whoop5RawImu's.
    private fun u16(f: ByteArray, o: Int): Int =
        (f[o].toInt() and 0xFF) or ((f[o + 1].toInt() and 0xFF) shl 8)

    private fun i16(f: ByteArray, o: Int): Int {
        val v = u16(f, o); return if (v >= 32768) v - 65536 else v
    }
}
