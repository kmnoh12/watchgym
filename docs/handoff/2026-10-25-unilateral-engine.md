# 원암 엔진 → Claude UI 연결

엔진만 구현. 새 버튼, 체크 색/모양, 워치 수동 화면, 전환 진동은 추가하지 않았다. UI 연결 전 배포하지 않는다.
기존 작업 트리에 다른 변경이 있었으며, 이를 되돌리거나 포함해서 커밋하지 않았다.

## 상태와 기록

`PlannedSetSnapshot.unilateral` / 폰 `PlannedSet.unilateral`을 사용한다.

| stage | 의미 | completed | 휴식 |
|---|---|---|---|
| WATCH_SIDE_WORKING | 워치 착용 팔 자동/직접 측정 | false | 없음 |
| AWAITING_OTHER_SIDE | 첫팔 완료, 반대팔 여부 선택 대기 | false | 없음 |
| OTHER_SIDE_WORKING | 반대팔 수동 수행 | false | 없음 |
| WATCH_SIDE_ONLY | 첫팔만 수행한 세트 최종 완료 | true | 시작 |
| BOTH_COMPLETE | 양쪽 수행한 세트 최종 완료 | true | 시작 |

- **첫팔 완료의 mint 체크는 `completed`가 아니라 `unilateral.watchReps != null`로 그린다.** `completed`는 전체 세트가 끝났다는 기존 의미를 유지한다.
- `watchReps` / `watchRepSource`는 착용 팔 근거, `otherReps`는 명시적으로 완료 확인한 반대팔 수동 횟수다. `otherReps == null`은 미확인/미수행이며 0회가 아니다.
- 기존 `performedReps`와 종목 합산 횟수는 한쪽 기준을 유지한다. 양쪽 합계 횟수나 볼륨을 표시하려면 위 두 값을 따로 사용한다. 센서 측정값을 2배로 만들지 않는다.
- 자동 종료와 폰/워치 수동 완료 모두 첫팔에서는 AWAITING_OTHER_SIDE에 멈춘다. 무응답 자동 시작/종료는 없다.
- FIRST는 왼쪽 고정이 아니라 **워치 착용 팔**이다. 실제 좌/우 표시는 기존 착용 정보(`patternWearing`)에 맞춘다.
- 반대팔 완료 시 횟수 생략은 첫팔 횟수와 같게 했다는 **사용자의 명시적 확인**이다. 화면에 목표/기본 횟수를 보이게 한다. 다르게 했다면 `otherReps`를 전달한다.

## 폰에서 호출

`PhoneSessionController(SessionStore(context), sendWear).unilateralAction(...)`:

```kotlin
controller.unilateralAction(
    sessionId, setId, attemptId,
    UnilateralAction.START_OTHER_SIDE, // 또는 FINISH_WATCH_SIDE_ONLY / COMPLETE_OTHER_SIDE
    System.currentTimeMillis(),
    otherReps = null, // COMPLETE_OTHER_SIDE에만 선택적으로 실제 횟수 전달
    commandId = stableId, // 같은 요청의 재전송은 같은 ID
)
```

- 완료 결과 `ack.status`를 확인한다. 폰이 저장한 뒤 워치 SYNC를 보낸다.
- UI는 성공 후 **저장된 snapshot을 다시 적용/refresh**해야 한다. 기존 Activity `sessionStore`의 관측 revision도 새로 읽어야 후속 편집 저장이 충돌하지 않는다.
- 폰 체크 두 번째 탭은 **OTHER_SIDE_WORKING에서만** 반대팔 완료로 사용한다. AWAITING_OTHER_SIDE에서는 두 선택 버튼을 보여준다.
- 기존 일반 완료 경로도 OTHER_SIDE_WORKING에서는 반대팔 수동 완료로 동작한다. 최종 완료 뒤 기존 완료 취소는 유지되며 원암 정보도 초기화된다.
- `requestAllSetsCompletion`의 일괄 완료는 원암에서 막아 두었다. 좌우 의사 없이 여러 세트를 최종 완료하면 중간 상태를 우회하기 때문이다.

## 워치에서 호출 / 읽기

새 action 3개를 기존 `WatchWorkoutControl`에 추가했다:
`START_OTHER_SIDE`, `FINISH_WATCH_SIDE_ONLY`, `COMPLETE_OTHER_SIDE`.

기존 `WORKOUT_CONTROL_SUBMIT` service intent에 `payload = control.encode()`로 전달한다.
기존 `WatchWorkoutOutbox`의 영속 재전송/ACK 경로를 그대로 사용한다.
`COMPLETE_OTHER_SIDE`는 `reps = -1`이면 첫팔과 같은 횟수 확인, 0..9999면 직접 확인한 횟수다.
**반대팔 완료 버튼에 기존 로컬 `SET_COMPLETE`를 보내지 않는다.** 그 명령은 센서 측정 종료용이다.

`WearWorkoutService.ACTION_STATE`에 추가된 extras:

- `unilateralProgress`: `UnilateralProgress.decode(...)`로 복원하는 전체 상태
- `unilateralStage`: enum 이름
- `otherSideTargetReps`: 첫팔 완료 횟수, 없으면 -1
- `manualRequired`: 반대팔 수행 중 true
- `phase`: 선택 대기 `AWAITING_OTHER_SIDE`, 반대팔 수행 `OTHER_SIDE_WORKING`

이 두 phase에서 서비스 `running=false`, `resting=false`다. UI는 running만 보고 홈/대기로 보내지 말고 원암 phase를 우선 처리한다.
센서 카운터/자동 종료 타이머가 동작하지 않으며, 심박은 허용된 경우 계속 받을 수 있다.
기존 화면의 큰 `reps`는 첫팔 값이므로 반대팔 실시간 카운트처럼 보여주지 않는다.
세트/종목/무게는 기존 extras를 그대로 쓴다. `manualStatus` 대신 원암 action 결과는 기존 workout control ACK/status를 따른다.

## 전송 / 복원

- 기존 rich command index 24에 `UnilateralProgress`를 추가했다. 0..23 의미는 그대로다.
- 원암 상태가 바뀌면 `restRev`도 올려 동일 세트의 최신 SYNC가 무시되지 않게 했다.
- 폰 snapshot schema 8, 이전 1..7 읽기 지원. 과거 완료 기록의 양쪽 수행 여부는 추정하지 않는다.
- 폰·워치 재생성, ACK 전 재전송, 오래된 attempt, 반대팔 단계에서 재전송되는 SET을 처리한다.
- 기본 적용 종목은 `UnilateralExercises.ids`의 원암 28개 ID(현재 목록 27종과 과거 호환 1종)이다. 일반 케이블 로우/하이로우를 원암으로 임의 판정하지 않는다. 새 원암 종목을 만들면 이 목록에 등록하거나 계획 생성 시 `unilateral = UnilateralProgress()`로 명시한다.
- 폰·워치 모두 이 엔진이 있는 버전이어야 한다. 이전 워치 버전의 UI/phase 지원을 보장하지 않는다.

## 검증

현재 트리에서 아래 관련 테스트만 실행해 통과했다. 전체 테스트/빌드/린트/화면 테스트는 실행하지 않았다.

- `UnilateralSetTest`: 6개 통과 (상태 전환, 중복, 잘못된 순서/ID, 한쪽 완료, 분석 보정, snapshot 왕복)
- `UnilateralSessionTest`: 4개 통과 (폰 저장·복원·ACK·수동 완료·휴식·명령 codec)
- `UnilateralServiceTest`: 3개 통과 (ACK 전 대기, 반대팔 SET 재전송, 일반/일시정지 상태 재생성, 종료 정리)
- 위 두 Android 서비스 테스트 과정에서 `:phone:compileDebugKotlin`, `:wear:compileDebugKotlin` 통과.
- 실제 Watch8 설치/실측, 새 UI 연결/사용성, APK 생성/배포는 하지 않았다.

## 같은 날 목록 보완

`덤벨 로우`를 양손 벤트오버 종목(`bent_over_dumbbell_row`)으로 추가했다. 원암 엔진은 적용하지 않는다. 기존 `dumbbell_row` 별칭은 과거 원암 기록과 측정 설정을 보존하기 위해 계속 `one_arm_dumbbell_row`로 해석한다. 체스트 서포티드 원암 종목은 추가하지 않았다.

## 사용자 실제 루틴을 반영한 원암 목록 확충

원암 변형 10종을 새로 추가하고 모두 `UnilateralExercises.ids`에 등록했다. 목록 215종, 원암 엔진 대상 19종이다.

- 원암 케이블 익스터널 로테이션
- 원암 케이블 리버스 플라이
- 원암 머신 시티드 로우
- 원암 머신 하이 로우
- 원암 플레이트 로우
- 원암 머신 랫풀다운
- 원암 머신 리버스 플라이
- 원암 케이블 오버헤드 익스텐션
- 원암 케이블 컬
- 원암 덤벨 프리처 컬

기존 원암 케이블 레터럴 레이즈 등은 중복 추가하지 않았다. 기존 일반/양손 종목과 저장된 루틴을 일괄 변경하지 않았다. 원암 머신은 실제 좌우 독립 손잡이로 한팔 수행이 가능한 기구를 뜻한다. 새 익스터널 로테이션은 기존 외회전과 같은 손목 시작 후보와 C 등급을 사용하며, 기존 보정 데이터가 새 변형에서 검증됐다는 뜻은 아니다. 종목을 추가한 것만으로 Watch8 정확도 검증이 완료되는 것은 아니다.

목록 확충 검증: `WorkoutDataTest` 8개 및 `StartWristOrientationTest` 7개 통과. 폰/워치 Kotlin 컴파일 통과. 원암 신규 10종과 기존 케이블 사레레의 첫팔 완료가 휴식을 보류하는 것을 단위 테스트로 확인했다. 기존 사레레의 비어 있던 종목 그룹도 보완했다. 실제 Watch8 측정 정확도/새 UI/배포는 검증하지 않았다.

## 머신 풀다운 핀식 / 플레이트식 분리

현재 선택 목록은 218종이다. 아래 4종을 선택할 수 있다.

- `machine_pulldown_pin`: 머신 풀다운(핀식)
- `machine_pulldown_plate`: 머신 풀다운(플레이트식)
- `single_arm_machine_pulldown_pin`: 원암 머신 풀다운(핀식)
- `single_arm_machine_pulldown_plate`: 원암 머신 풀다운(플레이트식)

공통 PULL_RETURN / VERTICAL_PULL 레시피를 사용한다. 양손 둘은 기존 전체 세트 완료/휴식 흐름이고 원암 둘은 첫팔 완료 후 반대팔 선택에 멈춘다. 원암은 좌우 독립 손잡이로 한팔 수행이 가능한 기구를 뜻한다.
기존 미분류 원암 `single_arm_machine_lat_pulldown`은 새 선택 목록에서 제외했다. 과거 루틴/기록의 ID, 측정 프로필 해석, 원암 진행은 호환 유지한다. 핀식인지 플레이트식인지 추정해 기존 기록을 바꾸지 않는다. 기존 케이블 랫풀다운 및 일반 랫풀다운 항목은 그대로다.

핀/플레이트 분리 검증: `WorkoutDataTest` 9개 통과. 두 양손 종목의 즉시 휴식, 두 원암 종목의 선택 대기, 과거 미분류 원암 ID 호환을 확인했다. 폰/워치 Kotlin 컴파일 통과. APK 생성/배포 및 실물 워치 검증은 하지 않았다.

## 추가 요청 원암 7종

아래 7종을 별도 선택 항목으로 추가했다. 현재 목록 225종이다. 기존 일반 종목은 보존하며 새 ID에만 원암 진행을 적용한다.

- `single_arm_bayesian_curl`: 원암 베이시안 컬
- `single_arm_cable_kickback`: 원암 케이블 킥백
- `single_arm_dumbbell_kickback`: 원암 덤벨 킥백
- `single_arm_chest_supported_dumbbell_row`: 원암 체스트 서포티드 덤벨 로우
- `single_arm_machine_lateral_raise`: 원암 머신 레터럴 레이즈
- `single_arm_dumbbell_lateral_raise`: 원암 덤벨 레터럴 레이즈
- `single_arm_cable_front_raise`: 원암 케이블 프론트 레이즈

각각 기존 일반 종목의 공통 측정 family/recipe에 연결했다. 원암 덤벨 레터럴 레이즈는 일반 덤벨 레터럴 레이즈와 같은 팔 내림 시작 후보를 사용하되, 실제 시작 기준 확정/Watch8 정확도 검증을 뜻하지 않는다. 머신 레터럴 레이즈의 원암은 좌우 독립형 기구를 전제로 한다. UI/배포는 이번 변경에 포함하지 않았다.

원암 7종 추가 검증: `WorkoutDataTest` 9개와 `StartWristOrientationTest` 7개 통과. 새 종목의 첫팔 완료 후 휴식 보류 및 일반 종목과의 분리를 확인했다. 폰/워치 Kotlin 컴파일 통과. 실물 워치 정확도 검증과 배포는 하지 않았다.
