너는 워치짐(Wear OS 워치 + 안드로이드 폰, Kotlin) 앱의 안정성 담당이다. 작업 폴더: ~/Documents/Default Project/워치짐.
사용자가 운동 중에 앱이 자주 종료된다고 한다. 크래시 로그는 docs/handoff/crash-logs-2026-10-20/ 에 있다.

## 이미 확인된 것
- edit-0.2.17-last-exception.json: 운동 중(phase WORKING) IllegalArgumentException. R8 mapping(phone/build/outputs/mapping/ota/mapping.txt)으로 풀면 PhoneWorkoutService.send → WorkoutWearingStore.bind 의 require("Set identity was reused for a different exercise"). 원인: 운동 중 "운동 대체"가 세트 ID는 그대로 두고 운동만 바꿈. 이미 MainActivity.showExerciseAlternatives 에서 attemptId를 새로 발급하도록 고쳤고, MainActivity.sendWear 에 runCatching 방어막을 쳤다(git diff로 확인).
- fgs-0.2.16-last-exception.json: 0.2.16에서 ForegroundServiceStartNotAllowedException (PhoneWorkoutService.onStartCommand의 startForeground). 백그라운드 상태에서 서비스가 시작될 때 안드로이드 17이 거부. 0.2.17에서 같은 문제가 남았는지 확인 필요.
- 사용자는 "운동 대체 말고도 다른 데서 또 터질 것 같다"고 의심한다.

## 할 일
1. 앱 전체에서 "운동 중 앱을 죽일 수 있는 예외 경로"를 찾아라. 특히: (a) getOrThrow(), require(), check(), !!, 인덱스 접근(get/[]/removeAt/coerceIn), checkNotNull, Compose key 중복, 메인 스레드에서 잡히지 않는 예외, (b) 포그라운드 서비스 시작/백그라운드 제한, (c) 워치↔폰 동기화(WatchWorkoutController, PhoneWearBridge, PhoneCommandOutbox, WearSync 파싱), (d) 운동 중 편집(세트 추가/삭제, 운동 추가/삭제/대체/순서 변경, 단위 변경, 일시정지, 휴식 중 편집), (e) SharedPreferences/파일 저장 실패, (f) 프로세스가 죽었다 복원(restoreActiveWorkout, 상태 복원 시 인덱스 범위). 각 후보에 파일:줄, 어떤 상태에서 예외가 나는지, 심각도(앱 사망/기능 불가/무해)를 적어라.
2. 후보마다 "재현 조건"을 구체적으로 만들어라. 예: 같은 세트가 워치에 전송된 뒤 운동 대체/삭제/순서변경, 휴식 중 세트 삭제 후 추가, 완료한 세트 직후 편집, 워치 연결 끊김 중 편집 후 재연결, 앱 강제종료 후 복원, 마지막 운동 삭제, 세트 0개, 같은 운동 중복 추가.
3. 가능한 후보는 JVM 단위 테스트(core 또는 phone의 test, Robolectric이 이미 설정돼 있으면 사용)로 실제 시뮬레이션하라. 테스트로 돌릴 수 없는 것(안드로이드 OS 정책 등)은 코드 읽기 + 근거로 판단하고 "실기기 재현 필요"로 분류하라. 에뮬레이터(tools/emulator_bridge.py, tools/device_check.py)가 이미 있으면 써도 되지만, 없으면 억지로 만들지 마라.
4. 재현이 확인된 크래시만 고쳐라. 고칠 때는 "예외를 삼켜서 숨기기"가 아니라 상태가 일관되게 유지되도록 고치고(예: 세트 신원 재발급, 범위 보정), 복구 불가능한 경우에만 사용자에게 토스트로 알려라. 수정마다 회귀 테스트를 추가하라.
5. 비행기 모드/워치 끊김/저장 실패 같은 환경 실패도 앱이 죽지 않고 운동 기록이 보존되는지 따져라(운동 기록 손실은 크래시보다 나쁘다).

## 반드시 지킬 것
- 추측하지 마라. 코드를 읽은 근거(파일:줄)와 실제로 돌린 테스트 결과로만 말하라. 못 확인한 것은 "확인 못 함".
- git commit / push / 릴리스 / 배포 금지. 파일 삭제 금지. UI 디자인 변경 금지.
- 빌드: JAVA_HOME="$HOME/Documents/Default Project/안드로이드-에뮬레이터/jdk17/Contents/Home" 로 "$HOME/Documents/Default Project/안드로이드-에뮬레이터/gradle-8.11.1/bin/gradle" --offline. 동시에 두 빌드 금지.

## 산출물
docs/handoff/2026-10-20-crash-hunt-result.md:
1. 크래시 후보 표(위치 / 발생 조건 / 심각도 / 재현 방법 / 상태: 재현됨·코드로만 확인·불가).
2. 고친 것(변경 파일, 회귀 테스트 이름, 테스트 통과 로그 요약).
3. 못 고친 것과 사용자가 실기기에서 눌러볼 재현 절차(순서대로).
4. 확인한 것 / 확인하지 못한 것.