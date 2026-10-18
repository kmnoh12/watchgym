# Code map

Line numbers refer to this packet source snapshot. Full service source is supplied for context; start with the named functions. Do not assume paired calibration and ordinary workout execute the same lifecycle.

## core/src/main/kotlin/dev/kyungmin/kcoach/core/VerticalPressRepDetector.kt

- class VerticalPressRepDetector: 17
- fun add: 42
- fun beginCounting: 93
- private fun close: 99

## core/src/main/kotlin/dev/kyungmin/kcoach/core/ProfiledMotionRepDetector.kt

- enum class MotionFamily: 8
- class ProfiledMotionRepDetector: 68

## core/src/main/kotlin/dev/kyungmin/kcoach/core/ExerciseMotionCatalog.kt

- object MotionFamilyTemplates: 35
- smith_shoulder_press: 108
- dumbbell_shoulder_press: 110

## core/src/main/kotlin/dev/kyungmin/kcoach/core/TrackingProfileSnapshot.kt

- fun decode: 13
- historicalPress: 36, 38

## core/src/main/kotlin/dev/kyungmin/kcoach/core/SetEndGate.kt

- class SetEndGate: 4
- fun shouldEnd: 14
- fun waitNs: 31

## wear/src/main/kotlin/dev/kyungmin/kcoach/wear/WearWorkoutService.kt

- private fun configureTracking: 1286
- override fun onSensorChanged: 1496
- private fun beginMeasurementCounting: 1705
- private fun recordDetectedRepAt: 1723
- private fun vibrateShort: 2322
- private fun vibrateTargetReached: 2330

## core/src/main/kotlin/dev/kyungmin/kcoach/core/UpperBodyPoseCounter.kt

- enum class CameraMotion: 7
- class UpperBodyPoseCounter: 69

## core/src/main/kotlin/dev/kyungmin/kcoach/core/CapturePurpose.kt

- enum class CapturePurpose: 3

## phone/src/main/kotlin/dev/kyungmin/kcoach/phone/PhoneSessionController.kt

- class PhoneSessionController: 7

## tools/mmfit_paper_replay.py

- def analyze: 34
- def main: 106

## core/src/test/kotlin/dev/kyungmin/kcoach/core/VerticalPressTimingCandidate.kt

- class VerticalPressTimingCandidate: 17
- closedLobes: 21, 121

## core/src/test/kotlin/dev/kyungmin/kcoach/core/VerticalPressReplayComparisonTest.kt

- class VerticalPressReplayComparisonTest: 11

## core/src/test/kotlin/dev/kyungmin/kcoach/core/PressBoundaryDiagnosticTest.kt

- class PressBoundaryDiagnosticTest: 10

## core/src/test/kotlin/dev/kyungmin/kcoach/core/CurrentDetectorEvaluationTest.kt

- internal object CurrentDetectorReplay: 13
- fun readSamples: 28

## Important source caveats

- B is still v1; C lives only in the test fixture. No runtime change to the recovery fraction occurred.
- A new Smith binding exists locally; frozen historical A profiles remain deliberately decodable. Last deployed APK predates this fix.
- The chronological investigation notes include corrected conclusions. Use the original CSV/source to audit, not chronology or assistant confidence.
- Haptics are local watch calls. Analyze sensor event time, callback delivery and checkpoint/haptic execution separately. No measurements proving a callback or persistence delay are supplied.
- Counting lobe timeout, inactivity set-end timeout and phone camera finish delay are distinct mechanisms.
- The paper baseline is offline and has no live vibration path.
