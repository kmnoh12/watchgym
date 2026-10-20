# 워치짐 운동 중 크래시 추적 결과 (2026-10-20)

대상: `docs/handoff/crash-logs-2026-10-20/`의 0.2.17 `edit`, 0.2.16 `fgs`, 0.2.17 `system-exits`.  
범위: 폰 앱 전체의 운동 중 예외 경로, 포그라운드 서비스, 워치 동기화, 운동 편집, 저장/복원.  
워치 UI/색/레이아웃은 건드리지 않았다. 커밋/push/릴리스/배포/기기 설치는 하지 않았다.

## 한눈에

- 0.2.17의 `IllegalArgumentException`은 `WorkoutWearingStore.bind`의 **“Set identity was reused for a different exercise”**(`phone/.../WorkoutWearingStore.kt:117`)다. 운동 대체 때 세트 신원이 그대로였던 것이 원인이고, 이미 `MainActivity.showExerciseAlternatives`가 `attemptId`를 새로 발급하고 `sendWear`가 예외를 잡도록 되어 있었다.
- 0.2.16의 `ForegroundServiceStartNotAllowedException`은 `PhoneWorkoutService.onStartCommand`의 `startForeground`에서 났다. 0.2.17에도 **같은 무방비 호출이 남아 있었다**.
- 이번에 추가로 막은 것: 서비스가 뒤늦게 처리하는 **오래된 바인딩 명령**, 웨어 메시지 경계의 **손상된 ACK/프로필**, 손상된 **활성/완료 세션 JSON**, 복원 **인덱스 초과**, 운동 삭제 중 **Compose 페이저 인덱스**, 편집 시트의 **오래된 세트 인덱스**, 시작 시 **백그라운드 서비스 시작 거부**, `homeReset` 저장 실패.
- 폰 단위 테스트 **288개 중 287 통과, 실패 0, 건너뜀 1**(카메라 원본이 필요한 선택형 1개). 새 Compose 회귀 테스트 1개도 통과.

## 1. 크래시 후보 표

상태 표기: **재현됨** = JVM/Robolectric 테스트로 실제 예외 경로를 밟음, **코드로만** = 코드 읽기로 경로 확인(OS/UI 정책이라 JVM 재현 불가), **불가** = 현재 자료로 확인 불가.

| # | 위치(파일:줄) | 발생 조건 | 심각도 | 재현 방법 | 상태 |
|---:|---|---|---|---|---|
| 1 | `WorkoutWearingStore.kt:117` | 세트를 워치로 보낸 뒤 운동을 대체해 같은 `setId:attemptId`가 다른 운동으로 재사용됨 | 앱 사망(메인 스레드) | `PhoneServiceHardeningTest.aStaleSetIdentityIsRejectedBeforeTheOutboxAndNeverThrows` | **재현됨 · 수정** |
| 2 | `PhoneWorkoutService.kt:70-71` | 백그라운드에서 서비스가 시작됐고 포그라운드 승격이 OS 정책으로 거부됨(0.2.16 로그) | 앱 사망(서비스 메인) | 실기기/OS 정책. JVM에서는 `promoteForegroundSafely`에 예외를 주입해 경계만 검증 | **코드로만 · 수정 · 실기기 필요** |
| 3 | `PhoneWorkoutService.kt:85` | `send`가 큐에 넣은 뒤 서비스가 늦게 처리하는 사이 운동이 대체되어 `sendRaw`가 다시 `bind`함 | 앱 사망(서비스 메인) | `PhoneServiceHardeningTest.onStartCommandContainsABindingFailureAndLeavesTheServiceAlive` | **재현됨 · 수정** |
| 4 | `PhoneWorkoutService.kt:242-279` | `bind` 실패, 아웃박스 저장 실패(`check(commit)`), `startForegroundService` 거부 | 앱 사망(호출자 메인) | `PhoneServiceHardeningTest`의 stale send/foreground 테스트 | **재현됨 · 수정** |
| 5 | `PhoneWearBridge.kt:111-125` | 브리지가 오래된 `PATH_COMMAND`를 직접 `bind`함 | 앱 사망(호출 스레드) | `PhoneServiceHardeningTest.theBridgeAlsoRejectsAStaleRawCommandWithoutThrowing` | **재현됨 · 수정** |
| 6 | `PhoneWearListenerService.kt:109-115` | 웨어 메시지 처리 중 예외(잘못된 ACK, 손상된 payload)가 리스너 메인으로 전파 | 앱 사망(리스너) | 손상된 pending으로 ACK 처리: `aMalformedCommandAckCannotKillTheWearListener` | **재현됨 · 수정** |
| 7 | `PhoneWearListenerService.kt:594` | `PhoneCommandOutbox.receiveAck`가 손상된 pending에서 인덱스 초과/파싱 예외 | 앱 사망(리스너) | 위 테스트(6)와 동일 | **재현됨 · 수정** |
| 8 | `PhoneWearBridge.kt:127-145` | 저장된 패턴 프로필 payload가 손상되어 `PatternProfileCommand.decode` 실패 | 앱 사망(서비스/워커) | `aMalformedStoredProfileTransferIsDroppedWithoutThrowing` | **재현됨 · 수정** |
| 9 | `MainActivity.kt:3158` | 워치 완료/새로고침 스냅샷의 세트 레이아웃이 현재 루틴과 달라 `restoreWorkoutRoutine`의 `require` 실패 | 앱 사망(메인) 또는 수신 경로 토스트 | 코드 경로. `completeWorkoutSet`은 직접 호출, 수신 경로는 `try/catch` | **코드로만 · 수정** |
| 10 | `MainActivity.kt:3123,3131` | 프로세스 사망 후 `exerciseIndex == exercises.size` 또는 `setIndex > lastIndex`로 복원 | 인덱스 초과/빈 화면 | `RestoredCursorTest` | **재현됨 · 수정** |
| 11 | `ui/WorkoutScreen.kt:97,151,306` | 마지막 운동을 보는 중 운동 삭제/이동으로 `exercises`가 줄어듦 | 앱 사망(Compose `IndexOutOfBounds`) | `WorkoutScreenShrinkTest` | **코드로만 + Compose 테스트 · 수정** |
| 12 | `MainActivity.kt:2318,2327` | 세트 편집 시트를 연 뒤 목록이 바뀌어 `setIndex`가 범위를 벗어남 | 앱 사망(메인) | 코드 경로(모달 중 목록 변경) | **코드로만 · 수정** |
| 13 | `MainActivity.kt:2655,2685` | 운동 삭제/대체 다이얼로그를 연 뒤 인덱스가 바뀜, `alternatives[which]` 범위 초과 | 앱 사망(메인) | 코드 경로(모달 중 목록 변경) | **코드로만 · 수정** |
| 14 | `SessionStore.kt:284` | `activeSnapshot` JSON이 손상된 상태에서 운동 종료/`clearActive` 호출 | 앱 사망(메인) | `StorageCorruptionTest.aCorruptActiveSnapshotDoesNotThrowFromClearActive` | **재현됨 · 수정** |
| 15 | `FinishedWorkoutStore.kt:99,126` | 완료 기록 JSON이 손상된 상태에서 결과 화면이 `recordHits`/`usePounds` 호출 | 앱 사망(메인) | `StorageCorruptionTest.aCorruptFinishedRecordIsSkippedInsteadOfCrashingTheResultScreen` | **재현됨 · 수정** |
| 16 | `WorkoutTermination.kt:11` | 앱 시작 시 `homeReset` 제거 commit 실패 | 앱 사망(메인) | 코드 경로(저장 실패 주입 필요) | **코드로만 · 수정** |
| 17 | `MainActivity.kt:537-539` | 백그라운드 제한 상태에서 액티비티가 서비스를 `startService` | 앱 사망(메인) | 코드 경로. 실기기 백그라운드 제한 필요 | **코드로만 · 수정** |
| 18 | `MainActivity.kt:1298` | 운동/세트가 0개인 루틴으로 시작 | 기능 불가(빈 운동 화면) | 코드 경로 | **코드로만 · 방어 추가** |
| 19 | `MainActivity.kt:1625` → `FinishedWorkoutStore.finish` | 완료 기록 저장 commit 실패 | 앱 사망 가능(기존) | `showMyMountainDecision`이 이미 `runCatching`으로 감쌈 | **이미 보호** |
| 20 | `PhoneSessionController.kt`의 `SetIdentity.resolve(...)!!` | 완료 적용 뒤 세트 신원을 찾지 못함 | 기능 불가(ACK 누락), 앱은 생존 | `receiveCompletion` 전체가 `runCatching` | **이미 보호(로그만)** |
| 21 | `WorkoutTermination.stop` | 종료 journal 저장/`clearActive` 실패 | 기능 불가, 앱은 생존 | `stop`이 `Result.failure`, 호출자가 저널 유지 | **이미 보호** |

> 1번은 0.2.17 로그의 `WorkoutWearingStore -> C3.H3`, `bind -> b`, `require`와 일치한다. 2번은 0.2.16 로그의 `PhoneWorkoutService.onStartCommand`의 `startForeground` 프레임과 일치한다.

## 2. 고친 것

### 2-1. 서비스 포그라운드 승격 거부를 앱 사망으로 만들지 않음
- `PhoneWorkoutService.kt:19` `promoteForegroundSafely`: `startForeground`의 `RuntimeException`(포함: `ForegroundServiceStartNotAllowedException`)을 잡고 `false`.
- `onCreate`(`:47`)와 `onStartCommand`(`:70`)가 실패하면 `stopSelf`하고 `START_NOT_STICKY`. 이미 아웃박스에 저장된 명령은 다음 포그라운드 시작에서 재시도된다.
- `stopForeground`를 호출하는 모든 경로(`onTaskRemoved`, `onTimeout`, `stopIfIdle`)에서 `foregroundReady=false`로 재승격을 보장.

### 2-2. 오래된 세트 신원이 서비스/브리지에서 다시 터지지 않게
- `PhoneWorkoutService.send`가 `bind`/`enqueue`/`startForegroundService` 실패를 `Boolean(false)`으로 돌려주고, `ACTION_COMMAND_REJECTED`(BINDING/OUTBOX/SERVICE_START/INVALID)를 브로드캐스트.
- `onStartCommand`의 비-durable `sendRaw`를 `runCatching`으로 감쌈.
- `PhoneWearBridge.sendPath`/`retryPendingCommand`/`dispatch`/`drain`이 오래된 명령·손상된 프로필을 로그만 남기고 건너뜀.
- `MainActivity.sendWear`가 `false`를 받으면 “워치 동기화에 실패했어요 · 세트를 다시 열어주세요” 토스트.
- 운동 대체 시 `attemptId` 재발급은 기존 `MainActivity.showExerciseAlternatives` 동작을 유지.

### 2-3. 웨어 메시지 경계
- `PhoneWearListenerService.onMessageReceived`/`onDataChanged`를 `runCatching` 경계로 감쌈.
- `PATH_COMMAND_ACK`의 `receiveAck`를 별도 `runCatching`으로 감싸 손상된 pending이 리스너를 죽이지 않게 함.
- `syncPersonalProfiles` 3개 호출부를 감쌈.

### 2-4. 복원·편집 인덱스
- `WorkoutRecovery.restoredCursor` 추가: `exerciseIndex`를 `0..lastIndex`, `setIndex`를 해당 운동의 `0..lastIndex`로 보정. `restoreActiveWorkout`이 사용.
- `applySnapshotToWorkout`의 `restoreWorkoutRoutine`을 `runCatching`으로 감싸고, 실패하면 현재 메모리 루틴을 유지하며 상태 문구 표시.
- `WorkoutScreen` 페이저 `initialPage` 보정, `key`의 `getOrNull` fallback, `ExercisePage`의 `getOrNull`, `page+1` 범위 확인.
- `MainActivity` 세트 추가/삭제 시 `setIndex` 범위 확인, 삭제 뒤 `workout.setIndex` 보정.
- 운동 삭제/대체 다이얼로그에서 인덱스 재확인, `alternatives[which]` 범위 확인.
- 빈 루틴 시작 방어.

### 2-5. 저장 손상/실패
- `SessionStore.clearActive`가 손상된 `activeSnapshot`을 읽을 때 예외를 밖으로 던지지 않고 `false`/정리 경로로.
- `FinishedWorkoutStore.recordHits`/`usePounds`가 손상된 완료 기록을 건너뛰고 빈 결과/기본값.
- `WorkoutTermination.consumeHomeReset`의 commit 실패를 로그로 남기고 플래그 유지.
- `PhoneWorkoutService.onCreate`의 카메라 STOP enqueue를 `runCatching`.
- `MainActivity` 시작 시 서비스 `startService` 두 곳을 `runCatching`.

### 변경 파일
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/PhoneWorkoutService.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/PhoneWearBridge.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/PhoneWearListenerService.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/MainActivity.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/ui/WorkoutScreen.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/WorkoutRecovery.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/SessionStore.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/FinishedWorkoutStore.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/WorkoutTermination.kt`

### 회귀 테스트
- `PhoneServiceHardeningTest` (6): stale send, stale `onStartCommand`, stale bridge, malformed ACK, malformed stored profile, foreground refusal.
- `RestoredCursorTest` (2): 마지막 운동/세트 초과, 빈 루틴.
- `StorageCorruptionTest` (3): 손상 active clear, 손상 finished record, stop failure.
- `WorkoutScreenShrinkTest` (1, Compose): 운동 3개 → 1개로 줄여도 페이저가 인덱스 초과하지 않음.

## 3. 테스트 로그 요약

| 실행 | 결과 |
|---|---|
| `:phone:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:phone:testDebugUnitTest --rerun-tasks` | **288 tests, 287 pass, 0 fail, 0 error, 1 skipped** |
| `:phone:testDebugCompose --tests '*WorkoutScreenShrinkTest'` | 통과 |
| `PhoneServiceHardeningTest` | 6/6 통과 |
| `RestoredCursorTest` | 2/2 통과 |
| `StorageCorruptionTest` | 3/3 통과 |
| 기존 `IdleServiceTest`, `WorkoutWearingStoreTest`, `BackgroundWorkoutTest`, `CommandAckTest` | 통과 |

건너뜀 1개는 `CameraEndFieldReplayTest`(카메라 원본 프레임이 필요한 선택형)다. 실패로 계산하지 않았다.

**빌드 환경 메모.** 프롬프트에 적힌 `JAVA_HOME=.../안드로이드-에뮬레이터/jdk17`과 Gradle 8.11.1로 시작했으나 `~/.gradle/native/.../libnative-platform.dylib` 로드 실패(`Could not initialize native services`)가 났다. 동일 JDK 17.0.19의 `~/.local/share/android-tooling/jdk17`과 `GRADLE_USER_HOME=/tmp/kcoach-gradle-home`, Robolectric용 `-I /tmp/kcoach-test.init.gradle`(`user.home=/tmp/kcoach-test-home`)으로 실행했다. 앱 코드와 무관한 환경 문제다.

## 4. 못 고친 것과 실기기 재현 절차

### 4-1. 포그라운드 서비스 거부의 실제 OS 동작
코드는 예외를 잡아 앱을 죽이지 않지만, **OS가 서비스를 실제로 거부한 뒤 알림/하트비트가 어떻게 보이는지**는 JVM에서 확인할 수 없다. 특히 Android 12+의 백그라운드 시작 제한, 제조사 배터리 최적화, Android 17 정책은 실기기에서만 확인된다.

실기기 절차:
1. 운동을 시작하고 폰을 홈으로 보낸 뒤 화면을 잠근다. 30초 이상 기다린다.
2. 워치에서 다음 세트/일시정지/종료 중 하나를 보낸다(또는 워치 앱을 다시 연다).
3. 폰이 죽지 않는지, 알림이 사라졌다가 돌아오는지, 명령이 나중에 적용되는지 확인한다.
4. 설정에서 워치짐 배터리를 “제한”으로 바꾸고 1~3을 반복한다.
5. `adb logcat -s KCoachEvent`로 `foreground promotion refused`/`ForegroundServiceStartNotAllowedException`를 확인한다. 서비스가 승격되지 않으면 명령은 아웃박스에 남고, 앱을 포그라운드로 올렸을 때 재시도되어야 한다.

### 4-2. 운동 대체·삭제·순서 변경
1. 운동 A의 세트를 워치로 보낸다(첫 세트 시작).
2. 앱에서 A를 B로 대체한다. 이때 앱이 죽지 않고, B의 세트가 새 `attemptId`로 워치에 다시 전송되는지 확인한다.
3. 마지막 운동을 보는 중 그 운동을 삭제한다. 화면이 검게/빈 화면이 되지 않는지 확인한다.
4. 운동 순서를 바꾸고, 현재 세트/휴식이 엉키지 않는지 확인한다.

### 4-3. 세트 편집·휴식
1. 운동 중 세트 추가/삭제를 반복한다.
2. 휴식 중 세트를 삭제한 뒤 추가한다. 휴식 종료 시 다음 세트가 맞는지 확인한다.
3. 완료한 세트 직후 편집한다(횟수/무게). 기록이 유지되는지 확인한다.
4. 세트 편집 시트를 연 채로 목록이 바뀌는 상황(가능하면 워치 완료/앱 편집)을 만들어 삭제 버튼을 누른다. 크래시가 없어야 한다.

### 4-4. 프로세스 사망 복원
1. 마지막 세트를 완료한 직후 앱을 강제 종료한다.
2. 다시 열어 커서가 마지막 운동/세트 범위 안에 있는지, 완료 세트가 유지되는지 확인한다.
3. 저장 파일을 손상시키는 것은 일반 사용자 절차가 아니므로, 필요하면 debug 빌드에서 `run-as`로 `shared_prefs/workout-session.xml`을 백업한 뒤 `activeSnapshot`을 깨뜨려 확인한다(데이터 백업 필수).

### 4-5. 비행기 모드/워치 끊김
1. 비행기 모드 또는 워치 끄기.
2. 운동 편집·세트 완료를 여러 번 한다.
3. 연결 복구 후 기록이 한 번만 반영되고, 중복 완료/휴식이 생기지 않는지 확인한다.
4. 이때 앱이 죽지 않고, 실패한 명령이 아웃박스에 남아 재시도되는지 확인한다.

### 4-6. 저장 실패
실기기에서 저장소를 가득 채우거나 `SharedPreferences.commit()` 실패를 주입하기는 어렵다. 최소한 앱 시작/운동 종료/결과 화면에서 “운동 기록 저장 실패 · 앱을 다시 열어 주세요” 토스트가 뜨고 프로세스는 살아 있는지 확인한다. `commit` 실패 시 최신 편집이 저장되지 않을 수 있지만, 앱을 죽이지 않고 기존 세션을 유지하는 것이 이번 수정의 목표다.

## 5. 확인한 것 / 확인하지 못한 것

### 확인한 것
- 0.2.17 `edit` 로그의 `C3.H3.b`가 `WorkoutWearingStore.bind`의 `require`(세트 신원 재사용)와 일치한다.
- 0.2.16 `fgs` 로그의 스택이 `PhoneWorkoutService.onStartCommand`의 `startForeground`와 일치한다. 0.2.17에도 같은 무방비 호출이 있었다.
- 오래된 세트 신원을 `PhoneWorkoutService.send`/`onStartCommand`/`PhoneWearBridge`가 처리할 때 앱이 죽지 않고 아웃박스가 보존되는 것을 테스트로 확인했다.
- 손상된 pending ACK, 손상된 active/finished JSON, 복원 인덱스 초과, Compose 운동 축소가 예외로 이어지지 않는 것을 테스트로 확인했다.
- 폰 단위 테스트 288개 중 287 통과, 실패 0, 건너뜀 1. 새 Compose 테스트 통과.
- 기존 서비스/아웃박스/백그라운드 완료 테스트가 그대로 통과한다.

### 확인하지 못한 것
- 실제 사용자 기기에서 `ForegroundServiceStartNotAllowedException`이 다시 나는지. 코드는 잡지만, OS가 서비스를 거부한 뒤의 알림/재시도 UX는 실기기에서만 확인된다.
- 운동 삭제/대체 중 Compose 페이저 크래시를 **수정 전 코드로 재현**하지는 못했다. 가드와 회귀 테스트로 현재 트리에서 인덱스 초과가 없음을 확인한 수준이다.
- 실제 저장소 가득 참/commit 실패를 실기기에서 재현하지 못했다. 코드 경로와 손상 JSON 테스트로만 확인했다.
- 제조사별 백그라운드 제한, Android 17의 최종 정책, 워치 연결 끊김의 실제 타이밍.
- APK 설치·실기기 실행·블루투스·화면 꺼짐·세션 복구 전체 흐름. 이번 작업은 소스+JVM/Robolectric 테스트까지다.
- 다른 워크트리 변경(`core` 엔진, `phone/MainActivity`의 기존 대체 운동 변경)과 이번 크래시 수정의 상호작용 전체. 크래시 수정은 폰 계층에 한정했다.
