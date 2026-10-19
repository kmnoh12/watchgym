# 2026-10-19 카탈로그·측정보정 작업 결과

기준: `2026-10-19-catalog-200-and-calibration-brief.md` 및 승인 CSV.
로컬 커밋만 작성. 푸시·배포·`tools/release.sh` 실행·기기 설치 없음.

## 등록

- 기존 50개 측정 바인딩 유지, 신규 152개 등록. 최종 202개(자동 158, 수동 44).
- CSV의 `barbell_back_squat`, `barbell_front_squat`는 기존 `barbell_squat`, `front_squat`와 중복이므로 생성하지 않음. 기존 `sit_up`, `squat` 유지.
- 신규 152개의 제안 패밀리·티어와 코드가 일치하며, 코어·폰 목록은 중복 없이 같은 202개 ID임을 대조함.
- `CatalogBetaExercises.exerciseIds`에 CSV의 Y 항목만 별도로 보관. 중복 스쿼트 ID는 기존 정식 ID로 기록. 앱에는 베타 표시를 추가하지 않음.
- 새 카메라 미지원 레시피 8개: `LOADED_HINGE`, `LOADED_LUNGE`, `HIP_EXTENSION`, `CORE_FLEXION`, `CORE_ROTATION`, `CORE_EXTENSION`, `DIP`, `SQUAT_PRESS`.
- 새 레시피의 카메라 자세 가이드·카운터는 null. 기존 레시피에 맞는 운동은 레시피를 재사용함.
- 시작 손목 방향은 기존 정책 유지: 새 수동 운동은 MANUAL, 새 자동 운동은 DEFERRED. 새 운동명만으로 시작 방향을 확정하지 않음.
- 측정 엔진 구현과 임계값은 수정하지 않음.

## 폰

- 202개 목록에 패턴·운동 묶음·기구 필터를 적용. 코어·전완·전신 및 새 동작 섹션 추가.
- 특수 기구는 머신·바벨 등 기존 필터 또는 기타로 분류.
- `ExerciseBrowser`를 운동 추가 화면과 보정 화면이 공유: 부위 탭, 검색, 기구 필터, 접이식 운동 묶음.
- 외회전 기본 선택 및 촬영 시작 버튼 제거. 가능한 운동을 누르면 기존 `CalibrationCameraActivity`로 직접 이동.
- 수동 전용 운동은 흐리게 표시하고 선택 차단. 스쿼트 레시피의 전신 촬영 예외 유지.
- 보정 저장 여부를 행에 표시. 저장된 프로필이 있는 행을 길게 누르면 기존 삭제 확인창으로 연결.
- 저장한 촬영, 제목 길게 누르기의 연구 모드, 진행 중 보정의 종료·저장 제어 유지.
- 설정의 보정 적용 스위치와 프로필 적용 코드는 변경하지 않음. 설명은 개인 보정 코드의 반복 시간·손목 신호 기준 조정 및 측정 방식별 적용 차이에 맞춤.
- 새 애니메이션·그림 없음. 누락 리소스는 기존 `exerciseAnimationView`의 플레이스홀더 반환 경로를 사용함(소스 확인).

## 검증

- 지정한 오프라인 전체 단위 테스트 명령 실행. `KCOACH_MMFIT_DIR`를 로컬 `work/datasets/mmfit-probe`로 지정해 MM-Fit 재생 포함.
- 코어 전체 실행: 400건 중 카탈로그 확장에 따른 기존 개수·카메라 지원 목록 단언 5건 실패, 4건 건너뜀. 해당 단언을 수정한 뒤 관련 카메라·분류 테스트 31건 모두 통과.
- 폰 전체 단위 테스트 및 updates 단위 테스트 통과.
- wear 138건 중 문서에 명시된 기존 `SetIdentityTest` 2건만 실패:
  - `aCaptureFromAnotherAttemptCannotSubstituteForMissingChunks`
  - `failedCaptureFinalizationRetainsTheRuntimeChunkReferences`
- 신규 레시피의 카메라 미지원, 런지/아놀드 패밀리, 수동 예외, 중복·베타 ID 검사 통과.
- 202종의 부위·기구·섹션 검사 및 기본 미선택/탭 복원 검사 통과. 이 검사에서 발견한 전신 힌지 섹션 누락은 수정 후 재검증 통과.
- `:phone:compileDebugKotlin`, `:wear:compileDebugKotlin` 통과.
- Compose/스크린샷 테스트는 API 변경에 맞게 소스를 수정하고 컴파일했으며 별도 UI 테스트 작업은 실행하지 않음.
- 기기의 실제 화면·셀카 촬영 흐름·물리 Watch8 정확도는 이번 작업에서 검증하지 않음. APK 생성·설치 없음.
