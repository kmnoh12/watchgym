# 2026-10-18 디자인·워치 저장/종료 진단 구현 인계

## 최종 범위와 상태

기준 HEAD `f5f7b66`. 시작 때 존재한 미추적 작업 요청문은 보존하고 최신 지시를 추가했다. 사용자 최신 판단은 "너무 호들갑아님? 누가 운동중에 업데이트를 누르냐"다. 수동 OTA와 운동의 복잡한 상호 잠금은 이번 작업에서 전부 제거했다. `OtaWorkoutGate`, 지속 `installHeld`, 폰 `WorkoutRuntimeLock` 결합, `SessionStore`/보정/운동 시작 차단, 원격 설치 대기 연동과 그 테스트는 남아 있지 않다. `phone/`, `updates/`는 기준 HEAD와 차이가 없다. 기존 업데이트 UX는 유지한다.

현재는 **소스와 좁은 회귀 테스트 작성까지** 완료했다. 현재 트리 Kotlin 컴파일·테스트 실행·새 렌더의 픽셀 비교는 환경 차단으로 미완료다. 주 에이전트가 정상 실행환경에서 아래 대상만 확인하고 필요하면 로컬 커밋한다. 이 작업자는 커밋·push·설치·배포를 하지 않았다.

## 디자인 소스 변경과 근거

| 대상 | 확인/변경 | 남은 검증 |
|---|---|---|
| 목표 초과 2번 | 변경 전 실제 `sample-over.png`와 소스 대조. 앱 링의 밀도 의존 반지름을 시안과 같은 432 격자 `r=209`, 두께 7로 수정. 측정 1-B 배치·초과 색 전환/두 번째 겹은 유지. 구분선은 `#1E1D1A` | 현재 트리 13/12회·20kg·118bpm·02:03 렌더와 시안 13회 프레임 비교 |
| 휴식 편집 | 기존 `sample-rest-edit.png`와 행/세그먼트 좌표 대조. 행 y=166/238, 버튼 폭 62, 숫자 31, 단위 16, 세그먼트 y=108/h44 유지. 버튼 글자 600, 선택/비선택 700/500으로 시안과 일치 | 0:45·방금 1세트/다음 2세트·12회/20kg·02:05 동일 값 렌더 비교 |
| 보조 장 | 기존 `sample-aux.png`와 심박 baseline 166.2, zone y=204/h16, 통계 y=290 대조. 이미 맞는 배치는 유지 | 112bpm·ZONE 2·32:10·9세트·02:05 동일 값 렌더 비교 |

편집 면 `#1C1B19`, 조절 `#34322D`, 큰 조절 `#4B4841`, 과거 선택 `#403E3A`를 구분했다. 다음 세트 선택은 세이지 글자/워시, 방금 세트 선택은 뼈색이다. 휴식 노브와 실측 화면의 차가운 회색/청록 잔재도 역할에 맞게 정리했다. 심박 존의 의미 있는 파랑/회청은 남긴다. 118bpm은 기존 참조 최대심박 185 기준 ZONE 2이므로 2번 **시안 샘플의 하트만** 파랑으로 정정했다. 존 계산은 수정하지 않았다.

Git에 있던 미사용 4b·4c DOM, 전용 `.sq`/`.half`/`.splitp` 스타일과 후보 선택 문구를 제거했다. 선택된 4a/4-1 모핑 스크립트와 연결 ID는 남겼다. 소스 비교로 모핑 스크립트가 HEAD와 완전히 같음을 확인했고, 추출한 스크립트의 `node --check`가 통과했다. `.bak` 파일은 만들지 않았다. 이 확인은 모션의 실제 재생/픽셀 검증을 대신하지 않는다.

## 저장 실패·예외 경계

- 청크 쓰기 후 `persistRuntime()` 실패를 성공으로 반환하던 경로를 수정했다. 원본 레코드는 파일 쓰기 전에는 비우지 않고, 파일 쓰기가 성공해도 상태 저장이 실패하면 false다. 파일이 생긴 다음 재시도에서는 그 파일을 다시 중복 추가하지 않는다.
- 단일 `capture-checkpoint.json` journal이 청크 파일명과 원래 session/set/attempt/샘플 수를 핀으로 고정한다. 청크 쓰기 전에 journal을 원자적으로 기록하고, preferences 상태 저장 성공 후 journal을 제거한다. 재시작은 남은 journal부터 읽는다. journal 제거 실패도 성공으로 숨기지 않는다. 별도 DB나 원본 기록 형식 전환은 없다.
- 파일 생성 이전에 프로세스가 죽었거나 디스크가 가득 차 실제 샘플 바이트를 쓰지 못한 경우, 존재하지 않는 샘플을 복구했다고 주장하지 않는다. 빠진 파일 참조는 기존 불완전 원본 보관 경계로 이어진다. 메모리의 미기록 샘플까지 복구를 보장하지 않는다.
- 최종 캡처 ID를 상태에 보존하고 재시도에 재사용한다. 최종 원본/manifest/outbox가 있어도 그 상태를 체크포인트하지 못하면 청크를 먼저 삭제하지 않는다. 청크 참조를 해제한 상태 저장에 성공한 뒤 해당 임시 청크만 정리한다. 원본의 기존 폰 인수 ACK 이후 삭제 계약은 유지한다.
- 명령 적용 체크포인트 실패 때 applied revision/fingerprint를 되돌리고 `APPLIED` ACK를 보내지 않는다. 저장 실패로 완료를 진행할 수 없으면 다음 휴식으로 성공 전환하지 않는다. 저장 복구 오류가 있는 완료 메시지는 성공처럼 재전송하지 않는다.
- 센서 콜백, 세트/휴식 타이머, 연결 watchdog의 예외 경계를 추가했다. 실제 실패 시 측정을 멈추고 원본/식별자를 유지한다. 측정이 끊긴 시도는 불완전 원본으로 남기며 실제로 측정하지 못한 구간을 채우지 않는다. 잘못된 수동 명령 입력처럼 상태를 바꾸지 않은 실패는 기존 명령 보류를 유지해 정상 측정까지 끊지 않는다.
- `WearCommandService`의 foreground service 시작 예외는 로컬 기록 후 ACK 없이 송신자의 기존 재시도를 기다린다. 소유 서비스의 FGS 시작 실패도 정상 시작으로 처리하지 않는다. 시작 실패 후 `onDestroy`가 아직 복원하지 않은 원래 상태를 빈 상태로 덮지 않게 했다. 실물 FGS 타입은 기존 주석의 의도대로 health를 명시한다.
- 기존 `check`/`require`/`!!`를 일괄 삭제하지 않았다. 센서 검출 알고리즘, 휴식 일시정지 심박 정책, 최근 앱 스와이프/명시적 강제 종료 폐기, closed-session/journal/outbox/ACK/중복 방지의 기존 의미와 KCS/wire 기록 형식은 유지한다.

## 워치 로컬 종료 진단

폰 `LocalExitDiagnostics`와 같은 JSON 키/파일(`last-state.json`, `last-exception.json`, `system-exits.json`)과 전역 예외 처리기 위임을 사용한다. 워치 전용 `runtime-events.json`은 마지막 32개 이벤트만 보관한다. 파일마다 최대 48KiB, 시스템 종료 이력 8개, 예외 원인 6단계·단계당 스택 32개로 제한한다. 저장 위치는 앱의 `noBackupFilesDir/exit-diagnostics`다. 예외 메시지·운동 사실·원래 session ID는 저장하지 않고 세션은 짧은 해시로 연결한다. 원격 전송과 외부 수집 서비스는 없다.

`service-launch-failed`, `service-start-failed`, `runtime-interrupted-*`, `recovery-attempt`, `recovery-restored`, `recovery-failed`를 구분한다. **복구 성공은 운동 도중 무중단 성공이 아니다.** 마지막 샘플 watermark는 실제 원래 monotonic timestamp이며 downtime 측정값은 만들지 않는다. 상태 파일은 phase 변경 또는 약 5초마다 갱신하며 마지막 파일의 watermark가 정확한 크래시 순간까지의 모든 샘플을 뜻하지 않는다. 이 진단은 운동 UI에 노출하지 않는다.

## ABI/용량 읽기 전용 확인

기존 OTA APK는 폰 60,607,320 bytes(약 57.80MiB), 워치 1,351,831 bytes(약 1.29MiB)다. 폰 네이티브 라이브러리의 비압축 합계는 arm64-v8a 10,577,592 / armeabi-v7a 7,436,096 / x86 15,012,680 / x86_64 13,096,536 bytes다. 기존 산출물 ZIP을 읽은 값이며 새 빌드 실측이나 이번 변경 후 크기가 아니다.

ADB 서버도 `could not install *smartsocket* listener: Operation not permitted`로 막혀 물리 폰 ABI를 확인하지 못했다. 따라서 ABI 필터와 debug/에뮬레이터 지원은 변경하지 않았다. 남은 확인은 실제 배포 대상 폰의 `ro.product.cpu.abilist`, 개인 배포 대상 목록, 그 뒤 선택한 OTA 빌드만의 별도 최적화 판단이다. 크기 확인을 위한 새 빌드·릴리스는 하지 않았다.

## 실행한 확인과 차단

- `git diff --check`: 통과.
- 선택된 정지 모핑 스크립트의 HEAD 동일성, `restAnim`/`restAnimBtn` 존재, 4b·4c와 전용 스타일 제거: 정적 확인 통과.
- 추출 시안 JS `node --check`: 통과.
- 기존 실제 세 화면 PNG 열람과 소스/시안 좌표·폰트·색 대조: 수행. 현재 트리의 새 픽셀 비교 완료는 아님.
- 정상 Gradle 명령은 작업 시작 전 `java.net.SocketException: Operation not permitted`로 종료. 재개 후에도 동일하여 더 반복하지 않았다. 코드 컴파일 오류나 테스트 실패로 분류하지 않는다. 직접 Kotlin 캐시 우회는 만들지 않았다.
- Browser 연결: `No browser is available`, 조회 목록 `[]`. 다른 브라우저 우회를 반복하지 않았다.

로그/작업 증거는 ignored `work/sol61-high/compile-2.log`, `compile-no-daemon.log`, `compile-resumed.log`, `source-checks.json`, `existing-apk-size.json`에 있다. 최초 `compile-1.log`는 JAVA_HOME 미지정으로 JVM을 찾지 못한 준비 오류이며, 뒤 명령에서는 기존 JDK17을 지정했다.

## 주 에이전트에게 남기는 가장 좁은 검사

전체 테스트/빌드/lint/clean/UI suite를 지시하지 않는다. 현재 트리는 아직 이 검사들로 검증되지 않았다. 기존 JDK17·SDK·Gradle 8.11.1을 사용하고 긴 출력은 작업 로그로 보낸다.

1. `:wear:compileDebugKotlin` 한 모듈 컴파일.
2. `:wear:testDebugUnitTest --tests '*WatchStorageBoundaryTest'` — 청크/체크포인트 실패, 원본 ID 복구, 실패 revision, 쓰기 재시도, 시작 실패 시 원래 상태 보존, 잘못된 명령 보류, 최종 캡처 ID 재사용.
3. `:wear:testDebugUnitTest --tests '*WatchExitDiagnosticsTest'` — 전역 처리기 위임·민감 메시지 제외·이벤트 크기/개수·복구와 끊김 구분.
4. 변경한 기존 복구/완료 계약에 바로 닿는 `AdversarialReviewTest.serviceRecreationWithinTheSameBootKeepsOneContinuousCapture`, `AdversarialReviewTest.bootCountSeparatesStreamsEvenWhenTheNewTimestampIsLarger`, `WatchCompletionServiceTest`만 필요에 맞게 필터 실행.
5. `:wear:testDebugUnitTest --tests '*WatchDesignFinishTest'` — 요청한 3장만 `wear/build/outputs/watch-preview/finish-{over-13-of-12,rest-edit,aux}.png`로 생성. 시안은 432px·웹폰트 로딩 후 2번의 13회 프레임, 4-2, 5만 동일 값으로 비교. 심박 맥동 등 애니메이션 시점 차이를 구분한다. 필요하면 선택 모핑의 `WatchWorkoutPagesTest.pausingARestMorphsTheScreenAndResumingMorphsItBack` 한 메서드만 추가한다.

이 검증과 새 세 화면 비교를 통과하기 전에는 "컴파일/테스트/디자인 최종 비교 완료"라고 보고하거나 배포 근거로 쓰지 않는다. 물리 Watch8의 OEM 센서/FGS/종료 이력·장시간 운동·정확도·무고장 여부는 여전히 별도 미확인이다. 휴식 정지 중 심박 센서를 켤지의 제품 결정도 그대로 남아 있다.


## 주 에이전트 최종 확인 (2026-10-18)

작업자 인계 당시의 환경 차단은 위에 보존했다. 주 에이전트의 실행환경에서는 대상 Kotlin 컴파일과 아래 좁은 검사가 완료됐다.

- 저장 경계 6개, 종료 진단 2개, 완료 서비스 2개, 기존 동일 부팅/다른 부팅 복구 2개: 12개 모두 통과.
- `WatchDesignFinishTest` 1개 통과. 현재 트리의 목표 초과 13/12회·휴식 편집·보조 장을 432px로 생성.
- `WatchWorkoutPagesTest.pausingARestMorphsTheScreenAndResumingMorphsItBack` 1개 통과.
- 격리된 headless Chrome으로 시안을 렌더했다. Roboto와 Noto Sans KR의 실제 로드를 기다린 뒤 목표 초과 애니메이션을 4초(13회)로 정지해 동일 값으로 비교했다. 세 장의 링 반지름·색, 숫자와 통계·버튼/세그먼트 배치가 맞음을 직접 열어 확인했다. 웹 시안의 바깥 케이스/테두리는 앱 UI가 아니며, 브라우저와 Android 기본 한글 글꼴의 글리프·굵기 차이는 남는다. 모든 픽셀이 동일하다는 뜻은 아니다.
- 작업자가 추가했던 물리 기기의 명시적 HEALTH foreground-service type 분기는 기존 2인자 호출로 되돌렸다. 이번 저장/진단 작업에서 기존 FGS 선택을 변경할 근거가 없고 에뮬레이터 dataSync 분기는 유지한다.
- `git diff --check` 통과. `phone/`와 `updates/`는 기준 `f5f7b66`과 동일하다.
- ADB 서버는 정상이나 연결 기기가 없다. ABI 제한은 하지 않았고 실제 폰/워치 설치·배포·장시간 무고장 검증도 수행하지 않았다.
- 폰의 사용자 진단에 기록된 FGS 시작 시간 초과는 기존 `c485626`/0.2.8 수정 대상이다. 캐시가 원인이라고 확인한 적은 없으며 다른 휴식 화면 종료와 실제 재발 여부는 미확인이다. 기존 `2026-10-17-phone-foreground-start-crash.md`를 근거로 구분한다. 변경 없는 폰 검사는 다시 실행하지 않았다.

산출물은 `wear/build/outputs/watch-preview/finish-*.png`, 시안 비교 렌더와 실행 로그는 무시되는 `work/sol61-high/`에 있다. 총 14개 대상 테스트가 통과했고 새 전체 빌드/전체 테스트/설치/배포/push는 하지 않았다.
