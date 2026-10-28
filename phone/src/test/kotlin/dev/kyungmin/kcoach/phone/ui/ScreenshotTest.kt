package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.SetAnalysisUi
import dev.kyungmin.kcoach.phone.resultMetricRows
import dev.kyungmin.kcoach.phone.ANALYSIS_ESTIMATE_NOTE
import dev.kyungmin.kcoach.phone.WorkoutData
import dev.kyungmin.kcoach.phone.WorkoutRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders screens to PNG without an emulator: `gradle :phone:recordRoborazziDebug`, then read build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { content() }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun home() {
        val routines = WorkoutData.defaultRoutines()
        val rows = routines.mapIndexed { i, r -> RoutineRowUi2(r, if (i == 0) null else i * 3) }
        val ui = HomeUi(
            week = listOf(
                WeekDayUi("월", 0.8, false, false), WeekDayUi("화", null, false, false),
                WeekDayUi("수", 0.5, false, false), WeekDayUi("목", null, true, false),
                WeekDayUi("금", null, false, true), WeekDayUi("토", null, false, true), WeekDayUi("일", null, false, true),
            ),
            weekCount = 2, rows = rows, longestRest = rows.lastOrNull(),
        )
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {})
        shot("home") { HomeScreen(ui, null, actions) }
    }

    @Test
    fun stats() = shot("stats") {
        StatsScreen(
            StatsUi(
                "10월",
                listOf("4회" to "운동", "270분" to "총 시간", "96세트" to "총 세트", "40,809kg" to "총 볼륨"),
                listOf("등·이두" to "32세트", "가슴·삼두" to "26세트", "어깨전문" to "24세트"),
                mapOf("가슴" to 9, "등" to 14, "어깨" to 6, "삼두" to 5, "이두" to 3, "하체" to 0),
            ),
        )
    }

    @Test
    fun statsEmpty() = shot("stats_empty") { StatsScreen(StatsUi("10월", emptyList(), emptyList())) }

    @Test
    fun menu() = shot("menu") { MenuScreen("0.7.1", MenuActions({}, { null }, {})) }

    @Test
    fun records() {
        val month = java.time.YearMonth.now()
        val records = listOf(
            WorkoutRecord("${month}-03", "어깨전문", 74, 24, 9120),
            WorkoutRecord("${month}-05", "등·이두", 68, 22, 7840),
            WorkoutRecord("${month}-09", "가슴·삼두", 81, 26, 10450),
        )
        shot("records") { RecordsScreen(records, month, "가슴·삼두", RecordsActions({}, {}, {})) }
    }

    @Test
    fun library() = shot("library") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val entries = WorkoutData.catalog.map { item ->
            val (order, title) = dev.kyungmin.kcoach.core.ExerciseTaxonomy.section(item.bodyCategory, item.pattern)
            LibraryEntry(item, order, title, dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyRank(item.bodyCategory))
        }
        LibraryScreen(entries, setOf("pull_up", "lat_pull_down"), LibraryActions({}, {}, {}), FavoriteUi(setOf("machine_fly", "dumbbell_pullover")) {}) { android.view.View(context) }
    }

    @Test
    fun calibration() = shot("calibration") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val blank = { android.view.View(context) }
        val exercises = listOf(
            dev.kyungmin.kcoach.phone.ExerciseDefinition("a", "랫풀다운", "등", "등", "케이블"),
            dev.kyungmin.kcoach.phone.ExerciseDefinition("b", "시티드 로우", "등", "등", "머신"),
        )
        val ui = CalibrationUi(
            exercises.map { LibraryEntry(it, 0, "당기기", 0) },
            "워치와 폰 셀카 카메라로 내 동작을 함께 기록해요.",
            mapOf("a" to "보정 저장됨", "b" to "촬영하여 보정"), setOf("a", "b"),
        )
        CalibrationScreen(ui, { false }, { CalibrationButtonUi("보정 종료·저장", true) },
            CalibrationActions({}, {}, {}, {}), { blank() })
    }

    @Test
    fun result() = shot("result") {
        WorkoutResultScreen(
            ResultUi(
                "등·이두", "68분 · 22세트 · 세션 12345678",
                listOf(ResultSectionUi("랫풀다운 · 세트 1", SetAnalysisUi("lat_pull_down", "랫풀다운", 0, 10, 10, 10, 0.87).resultMetricRows(),
                    listOf(2.9, 3.0, 2.8, 2.6, 2.9, 3.1, 3.3, 3.4, 3.6, 3.5))),
                ANALYSIS_ESTIMATE_NOTE,
                listOf(
                    RecordUi("랫풀다운", RecordTier.MAX_WEIGHT, "62.5kg", "이전 60kg · +2.5kg", value = 62.5, previous = 60.0),
                    RecordUi("시티드 로우", RecordTier.REPS, "50kg 12회", "이 무게 최고 횟수 · 이전 10회", reps = 12, previousReps = 10),
                    RecordUi("덤벨 컬", RecordTier.ESTIMATE, "추정 1RM 24 → 25kg", ""),
                ),
            ),
            {}, {},
        )
    }

    @Test
    fun resultUnknown() = shot("result_unknown") {
        val analysis = SetAnalysisUi("external_rotation", "외회전", 0, 10, 0, null, 0.0)
        WorkoutResultScreen(
            ResultUi("어깨전문", "12분 · 2세트 · 세션 12345678",
                listOf(ResultSectionUi("외회전 · 세트 1", analysis.resultMetricRows())),
                ANALYSIS_ESTIMATE_NOTE),
            {}, {},
        )
    }

    @Test
    fun routineDetail() = shot("routine_detail") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val routine = WorkoutData.defaultRoutines()[1]
        val rows = routine.exercises.mapIndexed { i, item ->
            RoutineRowUi(i, item.exercise.name, "${item.sets.size}세트 · ${item.exercise.muscles}", { android.view.View(context) })
        }
        RoutineDetailScreen(
            routine.name, rows, "시작", KIcon.Play, false,
            RoutineDetailActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}),
        )
    }

    private fun noActions(): WorkoutActions = java.lang.reflect.Proxy.newProxyInstance(
        WorkoutActions::class.java.classLoader, arrayOf(WorkoutActions::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoActions"
            else -> null
        }
    } as WorkoutActions

    private fun workoutModel(resting: Boolean): WorkoutUiModel {
        val now = System.currentTimeMillis()
        fun set(uid: Int, label: String, kg: String, reps: Int, done: Boolean, prev: String?) =
            SetUi(uid, label, kg, reps, done, label == "노멀" && false, "", 90, false, null, prev)
        val sets = listOf(
            set(0, "1", "50", 10, true, "45×10"),
            set(1, "2", "55", 10, true, "50×10"),
            set(2, "3", "55", 10, false, "50×9"),
            set(3, "4", "55", 8, false, null),
        )
        val ex = ExerciseUi(
            "lat_pull_down", "랫풀다운", "등, 이두", true, 90, 10, 20, 2, 4, 2, sets,
            volumeDone = 1050, volumeTarget = 2100,
            history = HistoryUi("지난번 45×10 · 50×10 · 50×9", 1840, "55kg로 올리기", "모든 작업 세트를 목표 횟수까지 채웠어요", true),
        )
        return WorkoutUiModel(
            "등·이두", false, false, now - 21 * 60_000L, 0L, 0L, 0, listOf(ex), false,
            if (resting) now - 20_000L else 0L, if (resting) now + 70_000L else 0L, 0L, "다음 랫풀다운 3세트",
            restingSet = if (resting) 0 to 1 else null,
        )
    }

    @Test
    fun workout() = shot("workout") {
        val context = androidx.compose.ui.platform.LocalContext.current
        ActiveWorkoutScreen(
            workoutModel(false), noActions(),
            LiveLine("Watch8 연결됨 · 랫풀다운 · 세트 3", "t").also { it.setTextColor(0xFF76DBB9.toInt()) },
            LiveLine("7/10회 · 55kg · 심박 112bpm", "t"),
        ) { android.view.View(context) }
    }

    @Test
    fun workoutResting() = shot("workout_rest") {
        val context = androidx.compose.ui.platform.LocalContext.current
        ActiveWorkoutScreen(
            workoutModel(true), noActions(),
            LiveLine("Watch8 연결됨", "t"), LiveLine("휴식 중 · 10/10회 · 심박 98bpm", "t"),
        ) { android.view.View(context) }
    }

    private fun overlayShot(name: String, sheet: Boolean, content: @androidx.compose.runtime.Composable (() -> Unit) -> Unit) {
        val state = OverlayState()
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(KColors.Bg)) {
                OverlayHost(state)
            }
        }
        compose.runOnUiThread { state.show(Overlay(sheet) { dismiss -> content(dismiss) }) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun sheetOptions() = overlayShot("sheet_options", true) {
        SheetOptions("운동 메뉴", listOf(SheetItem(KIcon.Swap, "운동 변경") {}, SheetItem(KIcon.Reload, "순서 바꾸기") {}, SheetItem(KIcon.Trash, "운동 삭제") {}))
    }

    @Test
    fun sheetChoice() = overlayShot("sheet_choice", true) {
        ChoiceSheet("세트 종류", listOf("노멀" to "기본 작업 세트", "웜업" to "무게를 올리기 전 준비 세트", "드롭" to "무게를 줄여 이어가는 세트")) {}
    }

    @Test
    fun dialogConfirm() = overlayShot("dialog_confirm", false) { dismiss ->
        ConfirmDialog("운동을 종료할까요?", "지금까지의 기록은 저장되지 않아요.", "계속 운동", "종료", dismiss) {}
    }

    @Test
    fun sheetBig() = overlayShot("sheet_big", true) { dismiss ->
        BigChoiceSheet("▶", KColors.Accent, "운동을 시작할까요?", "워치 앱을 열어 두면 반복을 자동으로 세요.", KColors.Muted, "취소", "시작", dismiss, {}, {}, true)
    }

    @Test
    fun swipeRevealOpen() = shot("swipe_open") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(KColors.Bg).padding(16.dp)) {
            SwipeRevealDelete(enabled = true, onDelete = {}) {
                androidx.compose.material3.Text("세트 행", color = KColors.White, modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(64.dp).background(KColors.Surface))
            }
        }
    }

    @Test
    fun sheetSet() = overlayShot("sheet_set", true) {
        fun seg(vararg labels: String, selected: Int) = labels.mapIndexed { i, l -> SegOption(l, i == selected) {} }
        SetSheet(
            "1세트", "케이블 익스터널 로테이션 · 25회",
            listOf(
                SegGroup("세트 종류", seg("일반", "웜업", "슈퍼", "드랍", "실패", "보조", selected = 1), "근육을 준비하기 위한 워밍업"),
                SegGroup("그립", seg("노멀", "오버", "언더", "뉴트럴", "더보기", selected = 0), "표준 그립"),
                SegGroup("운동 강도 (RPE)", seg("-", "6", "7", "8", "9", "10", "상세", selected = 4), "확실히 1개 더 할 수 있어요"),
            ),
            listOf(SheetAction("다음 세트로 복제", false) {}, SheetAction("세트 삭제", true) {}),
        )
    }

    @Test
    fun sheetMenu() = overlayShot("sheet_menu", true) {
        SheetOptions(
            "케이블 익스터널 로테이션",
            listOf(
                SheetItem(KIcon.Swap, "운동 대체", featured = true) {},
                SheetItem(KIcon.Clock, "휴식 시간", featured = true, value = "90초") {},
                SheetItem(KIcon.Reload, "지난 세트 불러오기") {},
                SheetItem(KIcon.Sliders, "파운드로 단위 변경") {},
                SheetItem(KIcon.Trash, "운동 삭제", destructive = true) {},
            ),
            subtitle = "어깨전문 · 21분 경과",
        )
    }

    @Test
    fun sheetPick() = overlayShot("sheet_pick", true) {
        ChoiceSheet(
            "운동 강도 (RPE)",
            listOf("선택 안 함" to "", "10" to "더는 못 해요", "9.5" to "1개 정도 더 할 수 있을 것 같아요", "9" to "확실히 1개 더 할 수 있어요", "8.5" to "아마 2개 더 할 수 있어요", "8" to "확실히 2개 더 할 수 있어요", "7" to "꽤 할 만했어요", "6" to "웜업 정도예요"),
            selected = "9.5",
        ) {}
    }

    @Test
    fun sheetPickGrip() = overlayShot("sheet_pick_grip", true) {
        ChoiceSheet(
            "그립",
            listOf("노멀" to "표준 그립", "오버핸드" to "손바닥이 몸 반대쪽을 향하는 그립", "언더핸드" to "손바닥이 몸 쪽을 향하는 그립", "뉴트럴" to "양손이 서로 마주 보는 그립", "와이드" to "어깨 너비보다 넓은 손 배치", "클로즈" to "어깨 너비보다 좁은 손 배치", "얼터네이트" to "한 손은 오버핸드, 다른 한 손은 언더핸드"),
            selected = "와이드",
        ) {}
    }
}
