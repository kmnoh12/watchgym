package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting exercises while the pager is open must not leave Compose indexing past the new list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkoutScreenShrinkTest {
    @get:Rule val compose = createComposeRule()

    private fun set() = listOf(SetUi(1, "1", "10", 8, false, false, "", 60, false))
    private fun exercise(id: String, name: String) = ExerciseUi(
        id, name, "이두", true, 60, 8, 0, 0, 1, 0, set(), 0, 80, null,
    )
    private fun model(names: List<Pair<String, String>>) = WorkoutUiModel(
        "테스트", false, false, 0L, 0L, 0L, 0,
        names.map { exercise(it.first, it.second) }, false, 0L, 0L, 0L, null,
    )
    private val actions = java.lang.reflect.Proxy.newProxyInstance(WorkoutActions::class.java.classLoader,
        arrayOf(WorkoutActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoActions"
            else -> null
        }
    } as WorkoutActions

    @Test fun deletingExercisesBehindThePagerDoesNotThrowOrBlankTheScreen() {
        val state = mutableStateOf(model(listOf(
            "dumbbell_curl" to "덤벨 컬",
            "lat_pull_down" to "랫 풀 다운",
            "seated_row" to "시티드 로우",
        )))
        compose.setContent {
            val context = LocalContext.current
            ActiveWorkoutScreen(state.value, actions, LiveLine("", "title"), LiveLine("", "telemetry")) { View(context) }
        }
        compose.onNodeWithText("덤벨 컬").assertIsDisplayed()
        compose.runOnIdle { state.value = model(listOf("dumbbell_curl" to "덤벨 컬")) }
        compose.waitForIdle()
        compose.onNodeWithText("덤벨 컬").assertIsDisplayed()
    }
}
