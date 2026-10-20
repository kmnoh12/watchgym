package dev.kyungmin.kcoach.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.acos
import kotlin.math.sqrt

/** Pure thresholds so the phone can be tested without a sensor. Values are starting points for device tuning. */
data class PhoneViewingSignals(
    val screenOn: Boolean,
    val unlocked: Boolean,
    val charging: Boolean,
    val inHand: Boolean,
    val tiltDegrees: Double,
    val stillMs: Long,
)

object PhoneViewingPolicy {
    fun isViewing(signals: PhoneViewingSignals): Boolean =
        signals.screenOn && signals.unlocked && !signals.charging && signals.inHand &&
            signals.tiltDegrees in 20.0..70.0 && signals.stillMs >= 1_000L
}

/**
 * Phone-side "actively looking at the phone" signal. It is intentionally only a suspicion source;
 * the watch asks the user before it lets that suspicion block a normal start.
 */
class PhoneViewingDetector(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)

    private val gravity = FloatArray(3)
    private var gravityReady = false
    private var lastMotionAtMs = 0L
    private var impactAtMs = 0L
    private var inHand = false
    private var tiltDegrees = 90.0
    private var started = false

    fun start() {
        if (started || sensors == null) return
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        started = true
    }

    fun stop() {
        if (!started) return
        sensors?.unregisterListener(this)
        started = false
        gravityReady = false
        inHand = false
        lastMotionAtMs = 0L
        impactAtMs = 0L
    }

    fun isViewing(): Boolean = PhoneViewingPolicy.isViewing(signals())

    internal fun signals(): PhoneViewingSignals {
        val now = SystemClock.elapsedRealtime()
        return PhoneViewingSignals(
            screenOn = power?.isInteractive == true,
            unlocked = keyguard?.isKeyguardLocked != true,
            charging = battery?.isCharging == true,
            inHand = inHand,
            tiltDegrees = tiltDegrees,
            stillMs = if (lastMotionAtMs == 0L) 0L else now - lastMotionAtMs,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
        val now = SystemClock.elapsedRealtime()
        if (!gravityReady) {
            event.values.copyInto(gravity)
            gravityReady = true
            return
        }
        for (axis in 0..2) gravity[axis] += (event.values[axis] - gravity[axis]) * 0.10f
        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (g < 1f) return
        val cosine = (gravity[2] / g).toDouble().coerceIn(-1.0, 1.0)
        tiltDegrees = Math.toDegrees(acos(cosine))
        val linear = sqrt(
            (event.values[0] - gravity[0]) * (event.values[0] - gravity[0]) +
                (event.values[1] - gravity[1]) * (event.values[1] - gravity[1]) +
                (event.values[2] - gravity[2]) * (event.values[2] - gravity[2]),
        ).toDouble()
        if (linear > 0.9) {
            lastMotionAtMs = now
            inHand = true
            if (linear > 2.5) impactAtMs = now
        } else if (impactAtMs > 0L && now - impactAtMs in 400L..1_500L && linear < 0.12) {
            // An impact followed by stillness is a placed phone, including an angled stand.
            inHand = false
            impactAtMs = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
