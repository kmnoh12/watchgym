# 워치짐 엔진 분석·수정 결과 (2026-10-20)

작업 범위: `core/` 엔진, 워치 런타임 라우팅, 폰 운동 목록/표기, 테스트.  
워치 UI(색/레이아웃)는 건드리지 않았다. 커밋/push/릴리스/배포/기기 설치는 하지 않았다.


## 한눈에

| 항목 | 결정 | 핵심 숫자 |
|---|---|---|
| 1 스미스 인클라인 벤치 | **고침**(이동 검출기) | 합성 10회: 0 → 10 |
| 2 5회 인식/12회 기록 | **코드 변경 없음(이미 올바름)** | 수동 12가 재분석 3을 이김 |
| 3 7초 세트 종료 | **고침**(움직임 인지형, 7초 유지) | 거절 반복 중에도 세트 유지 |
| 4 5cm 흔들기 vs 벤치 | **고침**(같은 이동 검출기) | 흔들기 7 → 0, 벤치 0 → 10 |
| 5 시작 2.5→2.0초 | **적용**(drift 0.20, 보정 3.0 유지) | 실제 5캡처 2.0초 ready |
| 6 폰 보는 자세 필터 | **불가능(범용)** | 시작 vs 폰 중력 7.7~16.4° 겹침 |
| 7 플랫 머신 벤치/목록 | **추가 + 이름 명확화** | 카탈로그 205 유지 |
| 8 머신 플라이 | **카메라/로그 실측 필요** | 평면 원호 MULTI 0/10 |
| 9·11 푸시다운 vs 오버헤드 | **고침**(라우팅 대비) | 고정 손목 0 → 10 |
| 10 표기명 | **고침**(ID 유지/별칭) | 205개, 기록 호환 |

## 0. 사용 가능한 센서 데이터 목록

실제로 원본 센서(`watch.kcs`)가 있는 자료는 **5캡처**뿐이다. 나머지는 로그·리플레이 CSV·공개 데이터다.

| 운동 | 파일 | 샘플 수 | 길이 | 사용자 리뷰 | 워치 기록 | 비고 |
|---|---|---:|---:|---:|---:|---|
| 케이블 로프 푸시다운 (`rope_extension`) | `work/evidence/9cf833a3/watch.kcs` | 1,624 | 32.496 s | 10 | 11 | 10/10/10 검증에 사용 |
| 케이블 로프 푸시다운 (`rope_extension`) | `work/evidence/cfc9dc8b/watch.kcs` | 1,566 | 31.351 s | 10 | 6 | 10/10/10 검증에 사용 |
| 케이블 로프 푸시다운 (`rope_extension`) | `work/evidence/9fb34269/watch.kcs` | 1,534 | 30.706 s | 10 | 7 | 10/10/10 검증에 사용 |
| 머신 프리처 컬 (`preacher_curl`) | `work/evidence/472a62d6/watch.kcs` | 1,673 | 33.475 s | 10 | 10 | 시작 자세/폰 접근 분석 |
| 머신 프리처 컬 (`preacher_curl`) | `work/evidence/f5f09b2d/watch.kcs` | 1,785 | 35.707 s | 10 | 10 | 시작 자세/폰 접근 분석 |
| MM-Fit 공개 데이터 | `work/datasets/mmfit-probe/w00..w20.csv` + `*_labels.csv` | 약 1,017만 데이터 행 | 21세션 | 616세트 라벨 | — | 왼손 TicWatch Pro, Watch8 아님 |
| 스미스 숄더 프레스(비공개) | `work/smith-519e3550-replay.csv` | 원본 없음 | — | 10(문서) | — | 리플레이 이벤트 10개만 존재 |
| 프레스 2캡처 | `work/press-two-captures/first.mp4`, `second.mp4`, `*.log` | 원본 KCS 없음 | — | — | — | 영상/실험 로그만 존재 |
| 측정 매트릭스/리뷰 | `docs/reviews/2026-10-13-exercise-measurement-matrix.csv`, `2026-10-17-vertical-press-comparison.csv`, `2026-10-19-mmfit-paper-sets.csv` 등 | — | — | — | — | 설계/평가 기록 |

MM-Fit 616세트의 운동별 구성: bicep_curls 59, dumbbell_rows 64, dumbbell_shoulder_press 60, jumping_jacks 57, lateral_shoulder_raises 56, lunges 62, pushups 65, situps 65, squats 64, tricep_extensions 64.

**원본이 없는 운동(이번 분석의 한계):** 플랫/인클라인/스미스 벤치, 머신 플라이, 케이블 스트레이트바 푸시다운, 시티드 로우, 랫풀다운, 레그 계열. 따라서 이 운동들의 필드 정확도는 주장하지 않는다. 코드 경로·합성 재현·같은 계열의 실제 데이터로만 판단했다.

---

## 1. 항목별 원인·검증·결정

### 1. 스미스 머신 인클라인 벤치: 절반도 못 세고 타이밍이 안 맞음

**증상.** 실제 10~12회 중 절반 이하만 카운트, 진동 타이밍도 어긋남.

**원인(파일:줄).**
- `core/.../ExerciseMotionCatalog.kt:42` — 벤치가 쓰던 `PRESS_RETURN`의 신호는 `COMBINED_MOTION`이다.
- `core/.../ProfiledMotionRepDetector.kt:250-252` — 그 신호는 `gyro + dynamic * 0.12`로, **자이로가 지배**한다.
- `core/.../ProfiledMotionRepDetector.kt:78` — 중력 추정 저역통과 `alpha=0.10`은 50 Hz에서 시정수 약 0.2초. 2~4초짜리 느린 벤치 이동을 “중력”으로 흡수해 동적 가속도를 거의 지운다.
- `core/.../ProfiledMotionRepDetector.kt:278` — 로브 유효 조건이 `면적 >= 0.07`로 매우 낮다. 손목을 5 cm 흔드는 회전만으로도 통과한다.
- 라우팅은 정상이다. `wear/.../WearWorkoutService.kt:1310`에서 모든 비회전 운동이 `ProfiledMotionRepDetector`로 가고, 별도 벤치 전용 경로는 없다. “카탈로그엔 있는데 다른 경로로 빠진다”는 증상은 아니었다.

**호평 운동과 비교(갈라진 칸).**

| 칸 | 스미스 인클라인 벤치(문제) | 케이블 오버헤드(사용자 호평) | 로프 푸시다운(3캡처 10/10/10) |
|---|---|---|---|
| family | `PRESS_RETURN` | `FLEXION_EXTENSION` | `FLEXION_EXTENSION` |
| 신호 | `gyro + 0.12·dynamic` | `ROTATION_ENERGY`(자이로) | `ROTATION_ENERGY` |
| 중력/기준 | 0.10 저역통과(≈0.2 s) | 시작 tilt 기준, 0.15 s 필터 | 동일 |
| 1회 정의 | 부호 로브 2개, 면적 0.07 | 시작 tilt 60° 이탈 → 20° 복귀 | 동일 |
| 실제 데이터 | 없음 | 직접 캡처 없음(같은 family) | 3캡처 10/10/10 |
| 결과 | 느린 이동 미검출, 흔들기 오검출 | 정상 | 정상 |

**운동자 관점 검증(합성).** 30 cm 세로 이동, 손목 회전 0.10 rad/s를 1.5/2.5/4.0초 템포로 10회씩 넣었다.
- 변경 전 `PRESS_RETURN`: **0 / 0 / 0회**.
- 28 cm 스미스 인클라인(수직 성분): **0회**.
- 5 cm 손목 흔들기 10회: **7회** (오검출).
- 변경 후 `TRANSLATION_PRESS_RETURN`: 30 cm **10/10/10회**, 28 cm **10회**, 30° 인클라인 수직 성분 22 cm **10회**, 5 cm 흔들기 **0회**.

**결정: 고침.** `TRANSLATION_PRESS_RETURN` family와 `TranslationPressRepDetector`를 추가했다. 중력 2.5초 추정 → 중력 투영 → 0.75초 누설 적분 → 80 ms 평활 → 양방향 로브 쌍. 시작이 위든 아래든 첫 로브를 받아 첫 회를 잃지 않는다. 속도 프록시 면적이 0.24 미만이면(합성 30 cm 왕복의 약 절반, 5 cm 흔들기는 이보다 작음) 버리고, 원시 가속도 크기 변화가 0.35 m/s² 미만이면 회전만 있는 동작으로 버린다. 관련: `core/.../TranslationPressRepDetector.kt:28-41,92-105,153-181`, `ExerciseMotionCatalog.kt:46-53`.

**한계.** 6초/1회(3초 내림+3초 올림)는 아직 0회다. 4초 템포까지는 10/10. 실제 스미스 인클라인 원본이 없으므로 “필드에서 맞는다”는 주장은 하지 않는다. 6초 극저속은 카메라 실측 대상이다.

---

### 2. 인식 5회, 실제 12회일 때 기록에 12를 넣는 흐름이 맞는가

**결론: 맞다. 다만 ‘자동 5’를 지우고 12로 덮는 것이 정답은 아니다.** 실제 수행 횟수를 사용자가 확정하는 것이 기록의 기준이고, 자동 추정은 별도 값으로 남아야 한다.

**코드 확인.**
- `core/.../ManualSetProtocol.kt:5-25` — 실제 수행 횟수는 필수이고 목표 횟수로 대체하는 fallback이 없다.
- `phone/.../SessionStore.kt:222-233` — 완료 세트를 수정하면 `performedReps`를 바꾸고 `repSource=MANUAL`로 표시한다.
- `core/.../WorkoutSessionReducer.kt:103-110` — 늦게 도착한 폰 재분석은 `PHONE_ANALYSIS`로만 교체하고, `MANUAL`은 절대 덮지 않는다.
- `wear/.../WearWorkoutService.kt:1733-1749` — 진동은 `reps += 1` 뒤에만 울린다. 즉 “진동이 났는데 워치 카운트가 0”은 이 경로에서 불가능하다.

**검증.** `ManualRepEditPrecedenceTest` 추가: 워치 5회(WATCH) → 폰 재분석 4회로 교체됨(PHONE_ANALYSIS) → 사용자가 12회로 수정(MANUAL) → 이후 재분석 3회가 와도 12회/MANUAL 유지. 통과.

**결정: 코드 변경 없음(이미 올바름).** 대신 5회에서 끝나는 원인(1·3·4·9)을 고치는 것이 맞다. 12를 넣는 것은 기록의 정답이지만, 센서 원본은 5회라서 템포·안정성 지표는 여전히 5회 기준이다. 이 한계는 기록에 “자동 추정 5 / 사용자 확정 12”로 남기는 것이 정직하다.

---

### 3. 세트 도중 7초 제한에 걸려 자주 종료됨

**원인(파일:줄).**
- `core/.../SetEndGate.kt:4-24` — 기존 종료 판정은 **마지막으로 카운트된 반복 시각**만 봤다. 검출기가 반복을 놓치면 “7초 동안 반복 없음”이 되어, 사용자가 계속 운동 중인데도 세트가 끝난다.
- `core/.../SetEndGate.kt:34-38` — 목표 전 7초, 목표 후 3초. 이 값 자체는 `docs/DESIGN.md:44`의 승인된 UX 근거가 있다.
- `wear/.../WearWorkoutService.kt:103-112`, `:1587-1591` — 100 ms 타이머와 센서 입력 양쪽에서 같은 gate를 호출한다.

**호평 운동과 비교.** 호평 운동(오버헤드/로프)은 반복이 꾸준히 들어와 `lastRepNs`가 계속 갱신되므로 7초가 문제되지 않았다. 문제 운동은 “반복을 놓치는 운동”이라 같은 7초가 먼저 도달한다. **갈라진 칸은 타이머가 아니라 검출 누락**이다.

**운동자 관점 검증.**
- 5 cm 흔들기만 하는데 세트가 유지되는가? → 운동으로 인정되는 로브가 없으면 `lastMotionNs`도 0.25 rad/s/0.25 m/s² 문턱을 넘지 않아 7초 뒤 종료. 정상.
- 무거워서 5초에 1회, 검출은 2회 놓침 → `lastMotionNs`가 계속 갱신되어 세트 유지. 정지 후 7초에 종료.
- 세트를 버리고 손을 내려놓음 → 7초 뒤 종료. 기존 UX 유지.
- 워치를 만지작거리며 7초 이상 움직임 → 세트가 더 유지될 수 있음. 이는 “운동 중”과 “정리 중”을 손목만으로 구분할 수 없는 한계다.

**결정: 7초를 늘리지 않고, 종료 기준을 ‘마지막 카운트’에서 ‘마지막 유효 움직임’까지로 확장.** `SetEndGate.shouldEnd(..., lastMotionNs)` 추가, `ProfiledMotionRepDetector.lastMotionNs`/`ForearmRotationRepDetector.lastMotionNs` 연결. 검출기가 거절한 반복도 실제 움직임이면 세트를 붙잡는다. `core/.../SetEndGate.kt:14-27`, `core/.../ProfiledMotionRepDetector.kt:112-113,196-222`, `wear/.../WearWorkoutService.kt:109-112,1587-1591`.

**테스트.** `SetEndGateTest`: 7초 안에 거절된 반복이 계속되면 종료되지 않고, 움직임이 멈춘 뒤 7초에 종료. 오래된 motion timestamp는 세트를 늘리지 못함. 통과.

**반박.** 사용자의 “7초를 완화” 제안은 방향이 반대다. 7초를 10~15초로 늘리면 진짜 세트 종료도 늦어지고, 검출 누락은 그대로다. “움직임이 있으면 세트는 살아 있다”가 더 정확하고 UX 근거도 덜 훼손한다.

---

### 4. 손목 5 cm 흔들기는 인식, 실제 벤치는 전혀 인식 안 됨

**원인.** 항목 1과 같은 뿌리다. `COMBINED_MOTION = gyro + 0.12·dynamic`이라 **손목 회전(흔들기)은 크게, 손의 이동(벤치)은 작게** 본다. 게다가 0.2초 중력 필터가 느린 이동을 지운다. “진폭 임계값이 낮다”만으로는 설명이 부족하고, **신호 선택과 중력 필터 시정수**가 진짜 원인이다.

**수치.** 변경 전: 5 cm 흔들기 10회 → 7회, 30 cm 벤치 10회 → 0회. 변경 후(이동 검출기): 흔들기 0회, 벤치 10회.

**운동자 관점 검증.**
- 정상 폼 2.5초 템포: 10/10.
- 무거워 4초 템포: 10/10.
- 아주 느린 6초: 0/10 → 아직 미지원.
- 세트 전 폰을 보며 손목만 5 cm 흔듦: 0/10.
- 벤치 중 손목 각도가 거의 고정되고 손만 이동: 10/10(이동 검출기).
- 손목 회전이 큰 덤벨 벤치: 10/10.

**결정: 고침.** `flat_bench`, `incline_bench`, `dumbbell_bench`, 디클라인/인클라인/스미스/원암/플랫 머신 벤치를 `TRANSLATION_PRESS_RETURN`으로 라우팅. `ExerciseMotionCatalog.kt:106-107,139-143,148,160`, `TranslationPressRepDetector.kt` 전체.

---

### 5. 시작 대기 2.5초 → 2초, 오인식은 늘지 않게

**원인/근거.**
- `core/.../StartPoseStabilityGate.kt:32` — 변경 전 `WORKOUT_HOLD_MS = 2_500L`.
- 같은 파일 `:14-24` — 실제 gate는 “손목이 조용한가”만 본다. 자세 분류기가 아니다.
- `wear/.../WearWorkoutService.kt:1325-1327` — 운동 세트는 `requireSetupMotion=false`, `stableDurationMs=WORKOUT_HOLD_MS`로 생성된다.

**실제 캡처 재생(5캡처의 준비 구간).**

| 캡처 | 2.5초 설정 ready | 2.0초 설정 ready |
|---|---:|---:|
| cfc9dc8b (로프) | 2,502 ms | 2,002 ms |
| 9cf833a3 (로프) | 2,560 ms | 2,060 ms |
| 9fb34269 (로프) | 2,542 ms | 2,041 ms |
| 472a62d6 (프리처) | 2,500 ms | 2,020 ms |
| f5f09b2d (프리처) | 2,519 ms | 2,020 ms |

5캡처 모두 2.0초에 안정 hold가 완성됐다. 2.5초는 0.5초를 더 기다린 것뿐이다.

**오인식 검증(중요).** 시작 gate는 자세를 거르는 장치가 아니라 **정지 대기**다. 실제 프리처 캡처의 마지막 6초(폰을 보며 앉아 있는 구간)도 2.0초/기존 drift 0.32에서 ready가 됐다(472: 2,864 ms, f5: 2,002 ms). 즉 “폰 보는 자세”는 2.5초에서도 결국 통과한다. 2.0초로 줄이면 그 통과가 약 0.5초 빨라질 뿐, 새로운 종류의 오인식이 생기는 것은 아니다. 다만 그 0.5초 동안 손목을 움직이면 헛카운트가 1~2회 더 들어갈 수 있다.

**보완.** `maxOrientationDrift`를 0.32(≈18°)에서 0.20(≈11.5°)으로 조였다. 실제 준비 구간의 최대 편차는 7.4°였으므로 정상 시작은 모두 통과하고, 18°까지 허용하던 느슨함은 줄었다. `StartPoseStabilityGate.kt:18,32`.

**결정: 적용.** 2.0초 + drift 0.20. (이후 2026-10-20에 실제 5캡처 1.50~1.56초 근거로 1.5초까지 추가 단축했다.) 테스트 `StartPoseStabilityGateTest`를 2.0초 기준으로 갱신했고, 1.8초는 아직 아니고 2.1초에 ready, 흔들면 hold가 리셋되는 것을 확인했다. 관련 웨어 테스트(시작/보정/세트 종료) 통과. **paired calibration은 기존 3.0초를 `PAIRED_CALIBRATION_HOLD_MS`로 분리해 유지**했다(워치 UI의 3.0초 표시와 일치).

**남은 위험.** “폰 보는 자세” 자체를 걸러내는 것은 이 변경으로 해결되지 않는다(항목 6).

---

### 6. 폰을 보는 자세를 시작 후보에서 걸러낼 수 있는가

**결론: 현재 워치 센서/코드로는 범용으로 불가능하다. 특정 운동별로 학습된 중력 목표를 넣는 것만 가능하고, 그 자료도 아직 없다.**

**코드 확인.**
- `core/.../StartWristOrientation.kt:18-26` — 시작 자세 후보는 `external_rotation`, `crossover_reverse_fly`, `dumbbell_lateral_raise` 3개뿐이고 나머지는 전부 `DEFERRED`.
- `core/.../StartPoseStabilityGate.kt:23,126` — `expectedGravity`가 있을 때만 중력 방향을 검사한다.
- `wear/.../WearWorkoutService.kt:1321-1325` — 일반 운동 세트는 `startDirectionSnapshot`이 비어 있으면 `expectedGravity=null`로 gate를 만든다. 즉 **일반 운동에는 자세 목표가 없다**.
- `core/.../StartWristOrientation.kt:29-34` — 후보가 없으면 `DEFERRED`/`UNCONFIRMED`로 표시하고 자동 확정하지 않는다.

**실제 데이터 확인(시작 자세 vs 폰 접근 구간의 중력 방향 각도).**

| 캡처 | 시작 준비 평균 중력 | 폰 접근 구간 평균 중력 | 각도 |
|---|---|---|---|
| f5f09b2d (프리처) | (-0.284,-0.570,-0.771) | (-0.22,-0.67,-0.71) | **7.7°** |
| 472a62d6 (프리처) | (-0.261,-0.406,-0.876) | (-0.23,-0.60,-0.77) | **12.8°** |
| 9fb34269 (로프) | (+0.475,-0.849,+0.232) | (+0.37,-0.79,+0.49) | **16.4°** |
| cfc9dc8b (로프) | (+0.449,-0.859,+0.246) | (-0.39,-0.73,+0.57) | 53.9° |
| 9cf833a3 (로프) | (+0.452,-0.812,+0.370) | (-0.38,-0.86,+0.33) | 49.4° |

프리처 f5는 시작 자세와 폰 접근 자세의 중력 방향이 **7.7°**밖에 차이 나지 않는다. 11.5°(0.20 rad) 목표를 넣으면 둘 다 통과한다. 472도 12.8°로 사실상 겹친다. 로프 9fb도 16.4°다. “폰을 눈앞에 들고 보는 자세”와 “프리처/로프 시작 자세”는 손목 중력 방향만으로 구분되지 않는다.

**세티드 로우 예외는 가능한가?** 특정 운동에만 `expectedGravity`를 넣고 다른 운동은 비워두는 방식은 코드상 가능하다(`StartDirectionRuntimeProfile`). 하지만 그 목표를 만들려면 그 운동의 “정상 시작”과 “폰 보는 자세”를 **사람이 라벨링한 캡처**로 학습·검증해야 한다(`ReviewedStartWristDirection.kt`). 현재 그런 검토된 목표는 0개다. 세티드 로우를 예외로 두는 것은 “목표를 넣지 않으면 자동으로 예외”이므로 가능하지만, 다른 운동의 목표부터 만들어야 한다.

**운동자 관점 검증.** 폰을 눈앞에 들고 2초 정지 → gate는 정상적으로 ready(테스트 `aStableWristPassesInAnyGravityDirectionSoThisIsNotAPostureGate`). 이후 폰을 내리거나 손목을 움직이면 반복으로 오인될 수 있다. 이 문제는 벤치/푸시다운처럼 이동 검출기로 바꾼 운동에서는 크게 줄지만, 컬/로우/레이즈처럼 회전 기반 운동에는 남는다.

**결정: 고치지 않음(불가능).** 대신 (a) 이동 검출기로 폰 손목 회전을 무시하는 운동을 늘리고, (b) 운동별 시작 목표를 만들 수 있는 캡처 체크리스트를 남긴다. “시작 자세만 걸러내는” 범용 필터를 코드로 넣으면 정상 시작을 거부하거나 폰 자세를 통과시키는 거짓 안전이 된다.

---

### 7. 플랫 머신 벤치가 없고, 디클라인/인클라인/플레이트 머신도 없다

**확인 결과.**
- **없던 것:** 누워서 하는 플랫 머신 벤치. 현재 트리에 ID가 없었다.
- **이미 있던 것:** 디클라인 바벨/덤벨, 인클라인 바벨/덤벨, 디클라인 머신, 인클라인 머신(핀식/플레이트), 플레이트 체스트프레스. `core/.../ExerciseMotionCatalog.kt:139-148`, `phone/.../WorkoutData.kt:493-502`.
- 사용자가 못 찾은 이유는 (a) 구버전 빌드, (b) “플레이트 체스트프레스”에 “머신/앉아서”가 없어서 검색·눈으로 찾기 어려웠던 점으로 보인다. 이름을 “플레이트 머신 체스트프레스(앉아서)” 등으로 바꿨다.

**변경.**
- `flat_machine_bench_press` 추가: “플랫 머신 벤치프레스”, 가슴/머신, `TRANSLATION_PRESS_RETURN`, B tier. `ExerciseMotionCatalog.kt:148`, `ReviewedMeasurementContracts.kt:54`, `WorkoutData.kt:502`.
- 앉아서 하는 머신 프레스 4종에 “(앉아서)”를 붙여 누워서 하는 것과 구분. `WorkoutData.kt:498-501`, `ExerciseMotionCatalog.kt:144-147`.
- 카탈로그 수는 205로 유지된다(중복 `rope_pushdown` 제거 + 플랫 머신 벤치 추가). `WorkoutDataTest`, `MeasurementCatalogCoverageTest` 통과.

**GIF/아이콘.** 새 ID `exercise_flat_machine_bench_press.gif`는 없다. `MainActivity.kt:2494`는 없으면 플레이스홀더를 보여준다. 앉아서 하는 체스트프레스 GIF를 붙이면 “누워서 하는 운동”에 잘못된 그림이 되므로 붙이지 않았다. 새 GIF가 필요하면 별도 자산 작업이다.

---

### 8. 머신 플라이: 반복마다 진동 1번, 카운트 성공 없음

**코드상 원인 후보.**
- `core/.../ExerciseMotionCatalog.kt:110` — `machine_fly`는 `MULTI_AXIS_RETURN`.
- `core/.../ExerciseMotionCatalog.kt:55` — 이 family는 `minimumTrajectorySpread=0.04`를 요구한다.
- `core/.../ProfiledMotionRepDetector.kt:278` — 로브가 유효하려면 보조축 에너지 비율이 0.04 이상이어야 한다.
- 펙덱은 전완 패드에 손목이 고정되고 팔이 한 평면 원호로 움직이는 경우가 많다. 이때 보조축 에너지가 0에 가까워 **정상 반복이 전부 거절**될 수 있다.

**합성 비교(평면 원호 10회, 자이로 0.5 rad/s; 흔들기는 단일축).**

| family | 평면 플라이 | 5 cm 단일축 흔들기 |
|---|---:|---:|
| `MULTI_AXIS_RETURN`(현재) | **0/10** | 0/10 |
| `CYCLE_AXIS_RETURN` | 10/10 | 9/10 |
| `FLEXION_EXTENSION`, tilt 75° | 10/10 | 0/10 |
| `FLEXION_EXTENSION`, tilt 45°/60° | 0/10 | 0/10 |

흔들기에 보조축 성분이 섞이면 `MULTI_AXIS`도 7/10까지 센다. `CYCLE_AXIS`는 평면 원호를 세지만 단일축 흔들기도 9/10으로 센다. `FLEXION_EXTENSION`은 60° 이상 기울어야 세고, 펙덱의 실제 손목 tilt가 60°를 넘는지는 데이터가 없어 모른다. **어느 쪽이든 실측 없이 바꾸면 한쪽 실패를 다른 쪽 실패로 바꾸는 것**이다.

**진동과 카운트의 관계(중요).** `wear/.../WearWorkoutService.kt:1741-1748` — 진동은 `postTargetGuard`와 `setEndGate.rep`를 통과한 뒤, `reps += 1` **다음**에 울린다. 따라서 “진동이 났는데 워치 reps가 0”은 이 코드 경로에서 불가능하다. 진동이 났다면 워치 카운트는 증가했다. 사용자가 말한 “카운트 성공이 없다”는 (a) 폰 기록에 반영되지 않음, (b) 세트 완료/목표 달성 진동이 아님, (c) 화면 갱신 문제 중 하나일 가능성이 높다. 이건 원본 캡처와 폰 로그 없이는 확정할 수 없다.

**결정: 프로덕션 라우팅 변경 없음 + 카메라/로그 실측 필요.** 근거: (1) 평면 플라이에서 family 불일치는 명백하지만, (2) 진동-카운트 모순은 검출기 문제가 아니고, (3) 대체 family는 흔들기 오검출 또는 60° tilt 가정이 필요하다. 다음 캡처에서 실제 펙덱 손목 tilt/가속도와 폰 기록을 함께 봐야 한다.

---

### 9·11. 케이블 삼두 푸시다운은 안 되고, 오버헤드 익스텐션은 잘 된다 — 대비 설명

**원인(파일:줄).**
- `core/.../ExerciseMotionCatalog.kt:111` — 스트레이트바 푸시다운은 `TRANSLATION_PRESS_RETURN`으로 바꿨다(변경 전 `PRESS_RETURN`).
- `core/.../ExerciseMotionCatalog.kt:112-113` — 오버헤드/로프는 `FLEXION_EXTENSION`(자이로·시작 tilt).
- 변경 전 `PRESS_RETURN`은 `gyro + 0.12·dynamic`이고 중력 필터가 0.2초라, **손목 회전이 거의 없는 스트릭트 푸시다운**(팔꿈치만 펴고 손은 아래로 이동)을 놓친다. 반대로 오버헤드는 전완이 머리 위에서 크게 회전하므로 `FLEXION_EXTENSION`의 tilt 이탈/복귀가 잘 잡힌다. 이게 “한쪽만 잘 되는” 대비의 핵심이다.

**실제/합성 숫자.**

| 검출기/운동 | 스트레이트바 푸시다운(고정 손목, 합성 45 cm) | 로프 푸시다운(실제 3캡처) |
|---|---:|---:|
| 변경 전 `PRESS_RETURN` | **0/10** | — |
| 새 `TRANSLATION_PRESS_RETURN` | **10/10** | 10/10/9 |
| `FLEXION_EXTENSION`(기존) | — | **10/10/10** |

오버헤드 익스텐션은 같은 `FLEXION_EXTENSION`을 쓰고 사용자는 잘 된다고 보고했지만, **오버헤드 원본 캡처는 이 작업에 없다.** 로프 푸시다운 3캡처가 회전 기반 family의 실제 근거다.

로프 캡처는 `RopePoseEvidenceTest`로 확인: frozen 11/6/7, current 10/10/10, `finish()`가 마지막 반복을 만들지 않음. 새 이동 검출기도 같은 로프 캡처에서 10/10/9로, 완전히 비활성은 아님을 확인했다.

**운동자 관점 검증.**
- 스트레이트바를 천천히 3초 템포로 내려도 이동 검출기는 10/10.
- 손목을 고정하고 팔꿈치만 펴는 스타일: 이동 검출기 10/10, 기존 0/10.
- 로프/오버헤드는 기존 `FLEXION_EXTENSION` 유지 → 기존 10/10/10 기록과 호환.
- 5 cm 흔들기: 이동 검출기 0/10.

**결정: 고침(라우팅).** 스트레이트바/리버스그립/원암 푸시다운은 `TRANSLATION_PRESS_RETURN`, 로프/오버헤드/라잉은 `FLEXION_EXTENSION`. `ExerciseMotionCatalog.kt:111-116,227-228`.

**반박.** “푸시다운도 오버헤드처럼 `FLEXION_EXTENSION`으로 보내면 된다”는 단순 해법은 스트릭트(고정 손목) 푸시다운에서 다시 0이 될 수 있다. 실제 로프 캡처에서는 FLEXION이 10/10/10이지만, 그 캡처에서는 전완 회전이 있었기 때문으로 보인다. 스트레이트바는 이동 기반이 더 안전하다.

---

### 10. 표기명: 스트레이트바/로프 푸시다운, 오버헤드 로프/스트레이트바 분리

**변경 전 문제.**
- `cable_pushdown` = “케이블 트라이셉스 푸시 다운” (그립 불명)
- `rope_extension` = “케이블 로프 트라이셉 익스텐션” (실제 동작은 푸시다운인데 “익스텐션”으로 표기)
- `rope_pushdown` = “로프 푸쉬다운” (위 항목과 중복)
- `cable_overhead_extension` = “케이블 오버헤드 트라이셉 익스텐션” (그립 불명)

**변경 후.**

| ID | 표기 | family |
|---|---|---|
| `cable_pushdown` | 케이블 스트레이트바 트라이셉 푸시다운 | `TRANSLATION_PRESS_RETURN` |
| `rope_extension` | 케이블 로프 트라이셉 푸시다운 | `FLEXION_EXTENSION` |
| `cable_overhead_extension` | 케이블 스트레이트바 오버헤드 트라이셉 익스텐션 | `FLEXION_EXTENSION` |
| `rope_overhead_extension` | 케이블 로프 오버헤드 트라이셉 익스텐션 | `FLEXION_EXTENSION` |
| `reverse_grip_pushdown` | 케이블 리버스그립 트라이셉 푸시다운 | `TRANSLATION_PRESS_RETURN` |
| `single_arm_cable_pushdown` | 원암 케이블 트라이셉 푸시다운 | `TRANSLATION_PRESS_RETURN` |

**ID/기록 호환.**
- 표기만 바꾼 ID는 그대로다. 기존 기록은 ID로 연결되므로 유지된다.
- 중복 `rope_pushdown`은 visible catalog에서 제거하고 `rope_extension`으로 alias했다(`ExerciseMotionCatalog.kt:318`). 기존에 `rope_pushdown`으로 저장된 루틴/기록은 `ExerciseMotionCatalog.normalizeExerciseId`로 같은 엔진을 쓰고, 루틴에 남아 있으면 `MainActivity`가 다시 목록에 추가한다. 기존 ID를 삭제하거나 기록을 변환하지 않았다.
- `TrackingProfileSnapshot.kt:34-46`에 flat bench/푸시다운/rope_pushdown의 **과거 PRESS_RETURN 스냅샷**을 허용하는 마이그레이션 예외를 넣었다. 진행 중이던 세트를 복구해도 저장된 검출기 설정으로 재생된다.
- `WorkoutData.kt:467-470,498-502,570-572`, `ReviewedMeasurementContracts.kt`, `ExerciseMotionCatalog.kt`, `ExerciseTaxonomy.kt:17`(삼두 푸시다운/익스텐션 섹션) 확인.
- GIF는 ID 기반(`MainActivity.kt:2494`)이라 기존 4개 GIF는 그대로 매핑된다. 새 `flat_machine_bench_press`만 GIF가 없어 플레이스홀더다.

**테스트.** `ExerciseRoutingConsistencyTest`(라우팅/표기/카탈로그 수), `MeasurementCatalogCoverageTest`, `WorkoutDataTest` 통과.

---

## 2. 고친 것 목록과 테스트 결과

### 고친 것

1. **`TRANSLATION_PRESS_RETURN` + `TranslationPressRepDetector` 추가**
   - 벤치/스미스/덤벨/디클라인/플랫 머신 벤치, 스트레이트바/리버스그립/원암 푸시다운.
   - 중력 2.5초 추정, 0.75초 누설 적분, 80 ms 평활, 양방향 로브, 0.24 이동 면적 바닥, 0.35 m/s² translation span.
   - `core/.../TranslationPressRepDetector.kt`, `ExerciseMotionCatalog.kt`, `ProfiledMotionRepDetector.kt`.

2. **세트 종료를 움직임 인지형으로**
   - `SetEndGate.shouldEnd(..., lastMotionNs)`, `ProfiledMotionRepDetector.lastMotionNs`, `ForearmRotationRepDetector.lastMotionNs`, 워치 서비스 연결.
   - 7초 정책은 유지.

3. **시작 hold 2.5초 → 2.0초, drift 0.32 → 0.20**
   - `StartPoseStabilityGate.kt`, 워치 테스트 갱신.

4. **먹힌 표기/목록**
   - `flat_machine_bench_press` 추가, 앉아서 머신 프레스 이름 명확화.
   - 스트레이트바/로프/오버헤드 표기 분리, 중복 `rope_pushdown` alias.
   - 과거 스냅샷 마이그레이션 예외.

5. **테스트 추가**
   - `TranslationPressRepDetectorTest` (합성 벤치/스미스/푸시다운/흔들기/첫 회/부분 반복).
   - `ExerciseRoutingConsistencyTest` (라우팅/표기/카탈로그/별칭).
   - `SetEndGateTest` 움직임 인지 2건.
   - `ManualRepEditPrecedenceTest`.
   - `RopePoseEvidenceTest` 실제 로프 3캡처 + 이동 검출기 probe.
   - `StartPoseStabilityGateTest` 2.0초/자세 비판정 1건.

### 테스트 결과 (현재 트리)

**Core 전체:** `:core:test` — **418개 중 408 통과, 실패 0, 오류 0, 건너뜀 10**.  
건너뜀 10개는 전부 외부 데이터가 없을 때 명시적으로 skip하는 선택형 테스트다(MM-Fit 616세트, 비공개 프레스 ZIP, 로프 캡처 등). 아래는 환경변수를 주고 실제로 돌린 결과다.

| 실행 | 결과 |
|---|---|
| `:core:test` (기본) | 418 tests, 408 pass, 0 fail, 10 skipped |
| `VerticalPressReplayComparisonTest` (MM-Fit 60세트) | frozen v4 MAE 4.833 / exact 4/60 / ±1 8/60 → vertical v1 MAE 0.667 / exact 25/60 / ±1 55/60 (변경 없음) |
| `CurrentDetectorEvaluationTest` (MM-Fit 616세트) | rows MAE 0.39 / ±1 95.3%, shoulder press 0.67 / 91.7%, triceps 0.28 / 95.3% (이번 패치가 건드리지 않은 현재 트리 기준) |
| `RopePoseEvidenceTest` (실제 3캡처) | frozen 11/6/7 → current 10/10/10, `finish()` 추가 반복 없음 |
| `RopePoseEvidenceTest` 이동 검출기 probe | 10/10/9 |
| `SelectedEvidenceReplayTest` (cfc9dc8b) | reviewed 10, frozen 6, current 10 |
| 합성 벤치(변경 전 → 후) | 30 cm 10회: 0/0/0 → 10/10/10 (1.5/2.5/4초) |
| 합성 스미스 인클라인(전 → 후) | 0 → 10 |
| 합성 5 cm 흔들기(전 → 후) | 7 → 0 |
| 합성 고정 손목 푸시다운(전 → 후) | 0 → 10 |

**Wear:** `:wear:testDebugUnitTest` — **138개 중 136 통과, 2 실패**. 실패 2건은 `SetIdentityTest`의 `failedCaptureFinalizationRetainsTheRuntimeChunkReferences`, `aCaptureFromAnotherAttemptCannotSubstituteForMissingChunks`로, **HEAD 클린 워크트리에서도 동일하게 실패**하는 기존 문제다(캡처 저장 실패 시 `completeSet`의 `check`가 예외를 던지는 동작과 테스트 기대가 어긋남). 이번 변경과 무관함을 HEAD 재실행으로 확인했다.

**Phone:** `:phone:compileDebugKotlin` 통과, `WorkoutDataTest`/`MeasurementCatalogCoverageTest` 7/7 통과.

**워킹 트리 참고.** `phone/.../MainActivity.kt`의 운동 대체/전송 예외 변경은 이 작업 시작 전부터 워킹 트리에 있던 변경으로, 내가 수정하지 않았다. 이번 작업의 변경 목록에는 포함하지 않는다.

**빌드 환경 메모.** 사용자가 지정한 `JAVA_HOME=.../안드로이드-에뮬레이터/jdk17`과 Gradle 8.11.1로 시작했으나 `~/.gradle/native/.../libnative-platform.dylib` 로드 실패(`Could not initialize native services`)가 났다. 동일 JDK 17.0.19의 `~/.local/share/android-tooling/jdk17`과 `GRADLE_USER_HOME=/tmp/kcoach-gradle-home`, Robolectric용 `-Duser.home=/tmp/kcoach-test-home`으로 실행했다. 앱/엔진 코드와 무관한 환경 문제다.

---

## 3. 카메라 실측이 반드시 필요한 것 (창원 헬스장 체크리스트)

코드/로그만으로 판단할 수 없는 것만 남겼다. 각 항목은 앱의 **원본 캡처(watch.kcs + manifest + 영상)** 와 **사람이 직접 센 실제 횟수/구간**을 같이 남겨야 한다. 목표 횟수를 정답으로 복사하지 않는다.

### A. 벤치/스미스 인클라인 (최우선)
- 운동: `flat_bench`, `smith_incline_bench_press`, `flat_machine_bench_press`.
- 세트: 각 3세트 × 10회. 무게는 가벼운 워밍업 1세트 + 8~12RM 2세트(예: 빈 바/40 kg/60 kg, 사용자 실제 중량).
- 템포: 2초/1회 1세트, 4초/1회 1세트. 가능하면 6초/1회도 1세트(현재 코드가 0회로 놓치는 극저속 경계 확인).
- 자세: 정상 폼, 손목은 바 위 중립. 카메라 측면 45°, 전신과 바가 프레임에 들어오게.
- 대조: 세트 전에 손목만 5 cm 위아래로 10회 흔들기. 이때 카운트가 0인지.
- 기록: 실제 횟수, 각 반복의 대략적 시각(영상 프레임), 무게, 템포, 벤치 각도, 워치 착용 손/위치.

### B. 머신 플라이(펙덱)
- 3세트 × 10~12회, 20/30/40 kg(사용자 실제).
- 카메라 정면 + 측면. 전완 패드 접촉 위치, 손목이 패드에 고정되는지, 팔이 한 평면인지 메모.
- 대조: 5 cm 손목 흔들기 10회.
- 기록: 실제 횟수, 각 반복 구간, 팔꿈치/손목 각도 변화(가능하면), 워치 카운트/진동/폰 기록. **폰 화면 녹화나 logcat도 같이** 남기면 “진동은 나는데 기록이 0” 문제를 바로 가른다.

### C. 케이블 푸시다운 3종 비교
- 스트레이트바 푸시다운, 로프 푸시다운, 오버헤드(로프/스트레이트바) 각 3세트 × 10회, 20/30/40 kg.
- 스타일: (1) 자연스럽게 손목이 따라가는 폼, (2) 손목을 고정하고 팔꿈치만 펴는 폼.
- 기록: 실제 횟수, 팔꿈치/손목 각도, 위팔 위치, 케이블 부착점.

### D. 시작 자세/폰 자세 라벨링 (항목 6 전용)
- 운동: 벤치, 시티드 로우, 케이블 푸시다운, 덤벨 컬.
- 각 5회: START 후 **폰을 눈앞에 들고 5초 정지** → 내려놓기. 이때 `WORKING`으로 넘어가는지, 헛카운트가 몇 회인지.
- 각 5회: 정상 시작 자세로 2초 정지 → 첫 반복. 이때 정상 카운트되는지.
- 기록: 시작 자세와 폰 자세의 중력 벡터(앱 캡처의 가속도), 손목 각도, 실제 라벨(정상 시작/폰/일상 동작). 이 라벨이 있어야 `ReviewedStartWristDirection`으로 운동별 목표를 만들 수 있다. **라벨 없이 코드로 “폰 자세”를 추정하면 안 된다.**

### E. 왜 코드/로그로 안 되는가
- 벤치/플라이/스트레이트바 푸시다운 원본 캡처가 0개다. 로프 푸시다운만 3개 있고, MM-Fit에는 벤치/플라이/케이블 푸시다운이 없다.
- “5 cm 흔들기”와 “정상 벤치”는 가속도 크기만으로 구분되지 않는다(이동 거리/속도 적분이 필요). 합성으로 방향은 증명했지만 실제 손목 착용 위치·그립·기구 마찰은 캡처로만 확인된다.
- 폰 자세는 프리처/로프 일부 캡처에서 시작 자세와 중력 방향이 7.7~16.4°밖에 차이 나지 않았다. 눈앞 폰 자세의 실제 중력/각속도 라벨이 필요하다.
- “진동은 있는데 카운트가 없다”는 진동 코드상 불가능하므로, 폰 기록/화면/세트 완료 로그가 필요하다.

---

## 4. 내가 확인한 것 / 확인하지 못한 것

### 확인한 것
- 5개 실제 캡처의 샘플 수·길이·리뷰 횟수, 로프 3캡처의 frozen 11/6/7 → current 10/10/10.
- 현재 트리의 카탈로그→family→검출기→웨어 서비스 라우팅. 비회전 운동은 모두 `ProfiledMotionRepDetector`를 거치고, 벤치/푸시다운이 다른 수동 경로로 새는 곳은 없다.
- 벤치 합성 10회를 변경 전 0회, 변경 후 10회로 세는 것. 5 cm 흔들기를 변경 전 7회, 후 0회로 세는 것.
- 스트레이트바 고정 손목 푸시다운 합성 10회를 변경 전 0회, 후 10회로 세는 것.
- 세트 종료가 “마지막 카운트”만 보던 결함과, 움직임 인지형으로 바꾼 뒤의 테스트.
- 시작 hold 2.0초/ drift 0.20에서 5개 실제 준비 구간이 ready 되는 것, 폰 접근 구간도 통과하는 것.
- 수동 12회 수정이 늦은 폰 재분석에 덮이지 않는 것.
- Core 418/408/0/10, Wear 138/136/2(HEAD에서도 동일), Phone 카탈로그 7/7.
- `rope_pushdown` 제거/alias 후 카탈로그 205개, 라우팅/표기 테스트.

### 확인하지 못한 것
- 실제 Watch8에서 스미스 인클라인/플랫 벤치/플랫 머신 벤치/머신 플라이/스트레이트바 푸시다운의 정확도. 원본 캡처가 없다.
- 머신 플라이의 “진동 1번, 카운트 없음”의 실제 원인. 진동 코드상 reps는 증가하므로, 폰 기록/화면/세트 완료 중 어디서 끊기는지는 실기 로그가 필요하다.
- 6초/1회 극저속 벤치. 현재 0/10.
- 펙덱의 실제 손목 tilt가 60°를 넘는지(→ `FLEXION_EXTENSION` 후보), 평면 원호의 보조축 에너지가 0.04를 넘는지(→ `MULTI_AXIS` 유지 가능성).
- 운동별 시작 중력 목표의 실제 값과 “폰 보는 자세”를 분리할 수 있는지. 라벨 캡처가 없다.
- 다른 사용자/다른 기구/다른 착용 위치에서의 거짓 시작·거짓 카운트·미인식. 합성과 1명 캡처 5개뿐이다.
- APK 설치·실기기 실행·블루투스/화면 꺼짐/세션 복구. 이번 작업은 소스+테스트까지다.
