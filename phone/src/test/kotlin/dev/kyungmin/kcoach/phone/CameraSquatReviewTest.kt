package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RetentionFixtureExtractor::class])
class CameraSquatReviewTest {
    @Test fun reviewedSquatStoresItsOwnMetricsAndKeepsThemAfterVideoRemovalWhileWristCountingStaysManual() {
        val app = RuntimeEnvironment.getApplication(); val captures = CalibrationCaptureStore(app)
        val clip = captures.begin("squat", "스쿼트", true, "phone").getOrThrow()
        repeat(21) { index ->
            val ms = index * 50L
            val bend = when { ms < 400 -> 0.0; ms <= 650 -> (ms - 400) / 250.0; ms <= 900 -> (900 - ms) / 250.0; else -> 0.0 }
            val image = MutableList(33) { CameraLandmark(.5, .5, 0.0, .95, .95) }
            val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, .95, .95) }
            image[0] = image[0].copy(y = .08 + bend * .08)
            for (side in 0..1) {
                val x = if (side == 0) -.04 else .04
                image[11 + side] = image[0].copy(x = .5 + x, y = .20 + bend * .08)
                image[23 + side] = image[0].copy(x = .5 + x + bend * .05, y = .42 + bend * .16)
                image[25 + side] = image[0].copy(x = .5 + x + bend * .12, y = .65)
                image[27 + side] = image[0].copy(x = .5 + x, y = .88)
                world[11 + side] = world[0].copy(x = x, y = -.45)
                world[23 + side] = world[0].copy(x = x)
                world[25 + side] = world[0].copy(x = x + bend * .2, y = .4 - bend * .15)
                world[27 + side] = world[0].copy(x = x, y = .8 - bend * .3)
            }
            val ns = 1_000_000_000L + ms * 1_000_000
            captures.appendFrame(clip.id, CameraPoseFrame(index.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, ms * 1000, 640, 480, 0, true, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(image), listOf(world), true)).getOrThrow()
        }
        captures.videoFile(clip.id).writeText("synthetic-finalized-video frames=21 step_us=50000")
        captures.finish(clip.id, true).getOrThrow()
        val records = (0..65).map { i ->
            val ns = 900_000_000L + i * 20_000_000L
            SensorCaptureRecord(SensorSample(ns, 0f, 0f, 9.8f, 0f, 0f, 0f, sensorAccuracy = 3),
                accelerationTimestampNs = ns, gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
        }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(clip.id, "squat", clip.setId, "raw-squat-review", 2, "NONE", records.size,
            50.0, 50.0, records.first().sample.timestampNs, records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(),
            0, "squat", "스쿼트", attemptId = clip.attemptId).let { it.copy(payload = it.payload + mapOf(
                "capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch", "watchEpochVerified" to "true")) }
        captures.attachSensor(CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, raw).getOrThrow()).getOrThrow()
        val clocks = CaptureClockStore(app)
        listOf("START" to 900_000_000L, "END" to 2_200_000_000L).forEach { (window, sent) ->
            val probe = clocks.prepareProbe(clip.id, window, sent, "phone", true).getOrThrow()
            clocks.acceptReply(ClockProbeProtocol.reply(probe, sent + 10_000_000, sent + 12_000_000, "watch", true),
                sent + 22_000_000, "phone").getOrThrow()
        }
        val frames = captures.frames(clip.id).getOrThrow()
        val cycle = RepCycle(frames[8].cameraTimestampNs, frames[13].cameraTimestampNs, frames[18].cameraTimestampNs, MusclePhase.ECCENTRIC)
        val store = CalibrationReviewStore(app)
        val saved = store.saveCycles(clip.id, listOf(cycle), true, 0).getOrThrow()
        assertNull(saved.cameraMetrics); assertNotNull(saved.squatMetrics)
        assertEquals(saved, CalibrationReviewStore(app).load(clip.id).getOrThrow())
        assertTrue(saved.squatMetrics!!.cycles.single().pelvisDropTorsoLengths > 0)
        assertEquals(MotionFamily.CYCLE_AXIS_RETURN, ExerciseTrackingProfiles.resolve("squat").family)
        val count = store.saveCount(clip.id, 1, saved.revision).getOrThrow()
        CalibrationVideoRetention(app).discardVideo(clip.id, count.revision, 0).getOrThrow()
        assertEquals(saved.squatMetrics, store.load(clip.id).getOrThrow().squatMetrics)
        assertTrue(cameraSquatMetricsText(saved.squatMetrics).contains("정지 미확인"))
        val zip = ByteArrayOutputStream()
        CalibrationEvidenceExport(app).write(clip.id, false, zip).getOrThrow()
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; entries[entry.name] = input.readBytes() }
        }
        val json = org.json.JSONObject(entries.getValue("review.json").toString(Charsets.UTF_8))
        assertEquals("REVIEWED_SQUAT_CAMERA", json.getJSONObject("cameraSquatMetrics").getString("source"))
        assertArrayEquals(raw, entries.getValue("watch.kcs"))
    }
}
