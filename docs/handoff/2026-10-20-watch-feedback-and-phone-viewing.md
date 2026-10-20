# 워치 피드백·폰 보는 자세 감지 (2026-10-20)

## 1. 12회 앰버 플래시: 원인과 수정

증상: 1세트 목표 10회에서 12회를 한 뒤 휴식, 다음 세트 정지 후 시작할 때 워치에 이전 12회가 앰버색으로 잠깐 보였다가 0회로 바뀜.

원인:
- 휴식 타이머가 0이 되면 `WearWorkoutService`는 `resting=false`, `resting` 해제 후 `broadcastState`를 보낸다. 이때 `currentPhase()`는 `sessionId`가 남아 있어 **`READY`** 를 반환한다(`WearWorkoutService.kt:2401`).
- `MainActivity.updateUi`는 `PREPARING`/`POSITIONING`일 때만 “준비”로 덮고, `READY` 분기가 없었다. 그래서 `reps=12`가 그대로 그려지고, `12 > targetReps(10)`이라 `WatchWorkoutFace.ORANGE`가 적용됐다(`MainActivity.kt:654-655`).
- 다음 `SET`이 도착해 `reps=0`이 되면 0으로 바뀐다. 즉 검출기 문제가 아니라 **휴식 종료와 다음 SET 사이의 표시 공백**이다.

수정:
- `MainActivity.kt:663`에 `READY` 분기를 추가해 `준비`를 **SAGE** 색으로 표시하고, 이전 세트 횟수를 live count로 그리지 않게 했다.
- 회귀 테스트: `WatchWorkoutUiTest.previousCountIsNotShownBetweenRestCompletionAndTheNextSet` — `phase=READY, reps=12, target=10`을 브로드캐스트한 뒤 TextView에 `12`가 없고 `준비`가 있는지 확인. 통과.

## 2. 카운트 진동 100ms

- `WearWorkoutService.REP_TICK_MS`를 `55L` → `100L`로 변경(`WearWorkoutService.kt:2511`).
- 진폭은 기존대로 `255`(amplitude control이 있을 때), 없으면 `DEFAULT_AMPLITUDE`(`:2453`).
- 55ms는 모터가 최대 진폭에 도달하기 전에 끝날 수 있어 체감이 작았다. 100ms로 늘려 “톡”이 아니라 “틱”으로 느껴지게 했다.

## 3. 휴식 3·2·1·0과 시작 직후 진동

기존에는 **워치에 휴식 카운트다운 진동이 없었다.** 워치 `WearWorkoutService`의 rest ticker는 상태만 갱신했고, 진동은 폰 `MainActivity.restCue`만 울렸다.

추가:
- 워치 rest ticker에서 3·2·1초: 70ms 1회, 255(`vibrateRestCountdown`, `WearWorkoutService.kt:2430`).
- 0초: 강한 3연타 “징징징” — 100ms on / 70ms off / 100ms on / 70ms off / 200ms on, 255(`vibrateRestEnd`, `:2437`).
- 0초 직후 시작 자세 1.5초가 안정되면 `beginMeasurementCounting()`이 `vibrateReady()` 호출(`:1799`). `vibrateReady`를 100ms + 70ms + 180ms, 255로 강화(`:2466`).
- 같은 초에 한 번만 울리도록 `lastRestCueSecond`로 중복을 막는다(`:1391`).

체감 순서: **휴식 3·2·1(짧은 틱) → 0초(강한 징징징) → 준비 자세 1.5초 → 강한 ready 징징 → 첫 카운트 100ms 틱**.

## 4. 폰 보는 자세 감지와 드드드득

### 폰 판정
`PhoneViewingDetector`/`PhoneViewingPolicy` (`phone/.../PhoneViewingDetector.kt:24-66`):

```
screenOn && unlocked && !charging && inHand
&& tiltDegrees in 20..70
&& stillMs >= 1000
```

- `inHand`: 폰이 움직이면 true, 내려놓는 충격 뒤 정지하면 false. 거치대에 비스듬히 올려둔 폰은 `inHand=false`.
- `tiltDegrees`: 중력에 대한 폰 화면 각도. 평평(0°)·주머니(90°) 제외.
- 1초 정지, 화면 켜짐, 잠금 해제, 충전 아님.
- 10초 이상 완전히 정지해 있어도 `inHand`가 유지되므로 “폰 보면서 멍때리기”도 잡힌다. 영상 거치대는 `inHand=false`라 빠진다.

### 전달
- 폰 heartbeat에 `phoneViewing` 플래그를 추가: `WearSync.heartbeat(device, phoneViewing)` (`WearSync.kt:156`), `PhoneWearBridge.sendHeartbeat`.
- `PhoneWorkoutService`가 3초 heartbeat마다 `viewingDetector.isViewing()`을 보낸다(`PhoneWorkoutService.kt:139`).
- 워치 `WearCommandService`가 heartbeat를 받아 `PhoneViewingStore`에 5초 유효로 저장(`WearCommandService.kt:122`, `PhoneViewingStore.kt`).

### 워치 동작
- 휴식 종료 후 `PREPARING`에서 시작 gate가 ready인데 `PhoneViewingStore.isViewing`이 true이면 `enterPhoneCheck()` (`WearWorkoutService.kt:1756`):
  - `phoneCheckPending=true`, gate reset, `PHONE_CHECK` phase, 드드드득 진동(`vibratePhoneCheck`, `:1789`: 35ms on/25ms off × 6, 255).
  - 워치에 AlertDialog “아직 휴식 중인가요?” + [운동 시작] / [휴식] (`MainActivity.kt:793-806`).
- [운동 시작]: `resolvePhoneCheck(start=true)` → pending 해제, 대기(`PREPARING`)로 복귀, 30초 재질문 억제. 1.5초 hold 뒤 시작.
- [휴식]: `resolvePhoneCheck(start=false)` → `phoneCheckResting=true`, 시작하지 않고 대기. 워치 움직임이나 폰이 내려가면 해제되고 다시 대기→1.5초 hold.
- 워치 움직임: pending/resting 해제, gate reset, 대기 복귀. 그 움직임은 카운트하지 않음.
- 폰이 내려가거나 화면 꺼짐/평평/주머니/충전: `PhoneViewingStore.isViewing=false` → pending/resting 해제.

### 안전장치
- 확인창은 **시작을 영구 차단하지 않는다.** [운동 시작]·워치 움직임·폰 내려놓기 중 하나로 항상 대기로 돌아간다.
- 같은 의심에서 30초 재질문 억제.
- 거치대 오탐은 `inHand=false`로 대부분 빠지고, 혹시 떠도 한 번의 확인으로 끝난다.

## 5. 테스트

| 실행 | 결과 |
|---|---|
| `:phone:compileDebugKotlin` / `:wear:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:phone:testDebugUnitTest` | **291 tests, 290 pass, 0 fail, 1 skip** |
| `:wear:testDebugUnitTest` | **142 tests, 140 pass, 2 fail** |
| `:core:test` | **418 tests, 408 pass, 0 fail, 10 skip** |
| `WatchWorkoutUiTest.previousCountIsNotShownBetweenRestCompletionAndTheNextSet` | 통과 |
| `WatchWorkoutUiTest.phoneSuspicionShowsTheRestOrStartChoice` | 통과 |
| `PhoneViewingPolicyTest` | 3개 통과 |
| `PhoneViewingStoreTest` | 통과 |
| `PhoneCheckStateTest` | 통과 |

Wear의 실패 2개는 `SetIdentityTest`의 캡처 복구 테스트로, **HEAD 클린 워크트리에서도 동일하게 실패**하는 기존 문제다(캡처 저장 실패 시 `completeSet`의 `check` 예외와 테스트 기대 불일치). 이번 변경과 무관하다.

## 6. 시작 대기 2.0 → 1.5초

- 실제 5캡처 모두 drift 0.20에서 **1.50~1.56초**에 안정 hold가 완성됐다. 1.5초로 줄여도 정상 시작이 잘리지 않는다.
- 폰 보는 자세 차단이 그 0.5초를 메워준다. 확인창/휴식 상태에서는 시작하지 않고, 폰이 내려가거나 움직이면 1.5초 hold 뒤 시작한다.
- paired calibration은 기존 3.0초(`PAIRED_CALIBRATION_HOLD_MS`)를 유지한다.

## 7. 한계

- 폰 감지 임계값(20~70°, 1초, pickup/setDown)은 **실기기 캡처로 튜닝하기 전의 시작값**이다. 거치대에 조용히 올려둔 폰을 `inHand=true`로 잘못 잡을 수 있고, 그때는 확인창이 한 번 뜬다(시작 차단은 아님).
- `AlertDialog`는 워치 UI에 새 확인창을 추가한다. 색/레이아웃을 새로 디자인한 것이 아니라 기존 AlertDialog를 쓴 것이다.
- 실제 Watch8에서 255 진동이 얼마나 강하게 느껴지는지는 `hasAmplitudeControl()`과 시스템 진동 세기에 달려 있어 실기기 확인이 필요하다.
- 폰 화면 상태/센서는 `PhoneWorkoutService`가 살아 있는 동안만 수집한다. 서비스가 백그라운드 제한으로 승격되지 않으면 heartbeat가 없어 감지도 없다.
