# Codex 작업 지시서 — 운동 200종 등록 + 측정보정 탭 개편

작성: 2026-10-19 · 요청자: 경민 · 작업 트리 기준 커밋 `4967155` (풀업 자동측정 연결)
**배포(`tools/release.sh`)는 하지 말 것.** 로컬 커밋까지만. 엔진 코드(`CycleAxisRepDetector.kt` 등)는 건드리지 말 것.

## 입력 파일
- `docs/catalog/2026-10-19-catalog-200-proposal.csv` ← **기준표**. 열: exercise_id, 한국어명, 부위, 기구, CSV상태, 코드상태, CSV패밀리, 현재코드패밀리, 제안패밀리, 티어, 베타, 근거수준, 근거
- 원본 목록: `~/Downloads/운동카탈로그-1차-200종.csv`

## A. 운동 등록 (백엔드)

### 규칙 (경민이 승인한 결정)
1. `코드상태=등록됨`인 50개는 건드리지 않는다 (현재 코드가 정답, CSV의 옛 패밀리 무시).
2. `코드상태=미등록`은 **제안패밀리/티어**대로 등록한다. 베타 표시는 패밀리 열과 별개로 `베타=Y`인 것만 목록으로 보관(앱 UI에는 아직 표시 안 함).
3. ID 중복 주의: 표의 `barbell_back_squat`, `barbell_front_squat`는 코드에 이미 `barbell_squat`, `front_squat`로 있다. **새로 만들지 말고 건너뛴다.** (코드의 `sit_up`, `squat`도 유지)
4. `assisted_pull_up`은 수동 유지. 슈러그 3종·리스트 컬 2종은 수동(손목 신호 부족).
5. 수동(`UNOBSERVABLE_MANUAL`, 티어 D)은 `manual(...)`로 등록.
6. 런지류(워킹/리버스/레터럴/커티시/스텝업/불가리안/코사크)와 `arnold_press`는 `VERTICAL_PRESS_RETURN` (MM-Fit에서 주기 엔진이 런지 +6.8회 과다, 수직축 엔진 97%).
7. 로우·사레레·삼두 익스텐션·푸시업·풀업 계열·하체 중량·코어 베타는 `CYCLE_AXIS_RETURN`.

### 건드릴 곳 (운동 1개당)
- `core/.../ExerciseMotionCatalog.kt` — `binding(...)`/`manual(...)` 한 줄 (`explicitBindings` 맨 아래 부위별로 정렬해 추가)
- `core/.../ReviewedMeasurementContracts.kt` — 레시피 계약(없으면 `ExerciseMeasurementContracts.resolve`가 `MANUAL`로 떨어져 phone 테스트 `MeasurementCatalogCoverageTest`가 깨진다). 레시피 재사용 기준: 프레스류→CHEST_PRESS/SHOULDER_PRESS, 풀다운→VERTICAL_PULL, 로우류→ROW, 컬→CURL, 플라이/리버스플라이→FLY, 풀오버→PULLOVER, 삼두→TRICEPS_EXTENSION, 레터럴/프론트레이즈→LATERAL_RAISE, 푸시업류→PUSH_UP, 풀업류→PULL_UP, 스쿼트류→SQUAT. 데드리프트·런지·코어 등 맞는 레시피가 없는 자동 운동은 `PULL_UP`처럼 **카메라 가이드 없는 새 레시피**(예: `LOADED_HINGE`, `LOADED_LUNGE`, `CORE_FLEXION`)를 `MeasurementRecipe.kt`에 추가하고 `CameraCaptureGuide.kt`/`CameraPoseGuide.kt`의 `when`에서 `null` 처리, `UpperBodyPoseCounter.kt`의 `CameraMotion.forExercise`가 `null`을 돌려주게 한다 (풀업 커밋 `4967155`를 그대로 참고).
- `core/.../StartWristOrientation.kt` — 정책이 모르는 ID를 어떻게 다루는지 확인하고 필요하면 추가.
- `phone/.../WorkoutData.kt` — `exercise(id, 이름, 근육, 부위, 기구)` 목록, `patterns`(라이브러리 섹션), `families`(비슷한 운동 묶기) 맵.
- `core/.../ExerciseTaxonomy.kt` — `sections`, `bodyOrder`에 새 부위(코어, 전완, 전신)와 섹션 추가. `phone/.../ui/LibraryScreen.kt`의 `Bodies`, `Equipments` 칩 목록도 맞춘다 (스미스·EZ바·원판·케틀벨·트랩바는 기존 칩에 매핑하거나 "기타" 추가).
- 운동 정보 문구는 `MainActivity.exerciseDescription`의 공통 기본 문구가 자동 적용되니 새로 쓰지 않는다. 애니메이션/그림은 **만들지 않는다**(나중에 경민이 만듦). 썸네일 없는 운동이 라이브러리/보정 화면에서 깨지지 않는지만 확인.

### 테스트
- 50개 기준으로 박힌 숫자 단언을 새 총 개수에 맞게 수정: `CapabilityMetadataTest`, `ExerciseTrackingProfileTest`, `ProfiledMotionRepDetectorTest`, `StartWristOrientationTest`, wear의 `StartWristPolicyServiceTest`.
- `CycleAxisRepDetectorTest.rowsLateralRaisesAndTricepsUseTheCycleAxisEngine`에 새 사이클 운동 대표 몇 개 추가.
- 실행(맥):
  ```
  T="$HOME/Documents/Default Project/안드로이드-에뮬레이터"; export JAVA_HOME="$T/jdk17/Contents/Home" ANDROID_HOME="$T/android-sdk" ANDROID_SDK_ROOT="$T/android-sdk"
  "$T/gradle-8.11.1/bin/gradle" --offline --continue :core:test :phone:testDebugUnitTest :wear:testDebugUnitTest :updates:testDebugUnitTest
  ```
  `KCOACH_MMFIT_DIR="$PWD/work/datasets/mmfit-probe"`를 export하면 MM-Fit 재생 테스트도 돈다.
- **알려진 기존 실패:** wear `SetIdentityTest` 2건 (`aCaptureFromAnotherAttemptCannotSubstituteForMissingChunks`, `failedCaptureFinalizationRetainsTheRuntimeChunkReferences`) — 깨끗한 HEAD에서도 실패. 이번 작업과 무관, 건드리지 않는다.

## B. 측정보정 탭 개편 (폰 UI)

현재 코드: `MainActivity.showCalibration()` (약 1126행~), 화면 `phone/.../ui/MiscScreens.kt`의 `CalibrationScreen`, 라이브러리 UX는 `ui/LibraryScreen.kt`.

경민 요구 (원문 요지):
1. 지금은 **케이블 외회전이 강제 기본 선택**(`selectedCalibrationExerciseId = "external_rotation"`). 이럴 이유 없음 → 기본 선택을 없앤다.
2. 상단 텍스트: 번호 5단계 안내 대신 **"측정보정이 무슨 작업인지, 현실적으로 어떤 보정이 가능한지"를 간결하고 읽기 좋게 몇 줄**. **워치와 폰 셀카 카메라로 함께 진행한다고 고지.** (초안: 1) 내 동작을 워치와 폰 카메라로 함께 기록해 반복 속도·움직임 크기를 내 기준으로 맞추는 작업이에요. 2) 가능한 보정은 반복 시간과 움직임 범위 기준이에요. 워치 착용 위치나 자세 분석은 하지 않아요. 3) 워치를 차고 폰 셀카 카메라로 촬영해요. — 실제 보정이 하는 일과 맞는지 `PersonalCalibrationProfileCodec` 등을 보고 사실 확인해서 문구를 다듬을 것.)
3. **"워치와 함께 영상 촬영" 버튼 삭제.** 운동을 고르면 **바로 촬영 모드**(`CalibrationCameraActivity`)로 이동.
4. 운동이 200종이라 목록은 일반 운동 추가 화면처럼 **부위별 탭 + 비슷한 운동 묶기(접이식) + 검색 + 기구 필터** UX를 그대로 재사용 (LibraryScreen의 목록 부분을 공용 컴포저블로 빼서 재사용하는 게 이상적).
5. 수동 전용 운동은 고를 수 없게(흐리게 + "수동 기록만") 하되 스쿼트류 "전신 촬영" 예외는 현재 동작 유지. 이미 보정된 운동은 행에 표시하고, 보정 삭제는 행 길게 누르기 → 기존 `confirmDeletePersonalProfile`로 접근하게 한다.
6. 기존 "저장한 촬영", 연구 모드(제목 길게 누르기) 동작은 유지.

`PersonalizationSetting`(설정의 측정 보정 적용 스위치)과 충돌하지 않게 둘 것. UI는 기존 팔레트(검정 배경, 세이지 #76DBB9 = 동작/상태, 본 색 = 텍스트/과거)를 따른다.

## 완료 기준
- 위 테스트 통과(기존 실패 2건 제외), 앱 컴파일(`:phone:compileDebugKotlin`, `:wear:compileDebugKotlin`).
- 논리 단위로 로컬 커밋(① 코어 등록 ② 폰 목록/분류 ③ 보정 탭 UI). 푸시·배포 금지.
- 끝나면 등록한 운동 수, 새로 만든 레시피, 건너뛴 ID, 테스트 결과를 보고.
