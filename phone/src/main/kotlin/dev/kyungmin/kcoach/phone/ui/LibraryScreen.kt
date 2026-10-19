package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kyungmin.kcoach.phone.ExerciseDefinition

/** One exercise plus where it sits in the browse tree. */
class LibraryEntry(val exercise: ExerciseDefinition, val sectionOrder: Int, val sectionTitle: String, val bodyRank: Int)

class LibraryActions(
    val back: () -> Unit,
    val custom: () -> Unit,
    val add: (ExerciseDefinition) -> Unit,
)

private val Modes = listOf("전체", "좋아하는", "자주하는", "최근", "커스텀")
private val Bodies = listOf("전체") + dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyOrder
private val Equipments = listOf("전체", "맨몸", "덤벨", "바벨", "머신", "케이블", "기타")

private sealed interface LibRow {
    data class Header(val title: String, val count: Int) : LibRow
    data class Family(val key: String, val title: String, val count: Int, val expanded: Boolean) : LibRow
    data class Item(val exercise: ExerciseDefinition, val indented: Boolean) : LibRow
}

@Composable
fun LibraryScreen(
    catalog: List<LibraryEntry>,
    frequent: Set<String>,
    actions: LibraryActions,
    thumbnail: (ExerciseDefinition) -> View,
) {
    var mode by remember { mutableStateOf("전체") }
    var body by remember { mutableStateOf("전체") }
    var equipment by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    val expandedFamilies = remember { mutableStateMapOf<String, Boolean>() }

    val filtered = catalog.filter { entry ->
        val item = entry.exercise
        (query.isBlank() || item.name.contains(query, ignoreCase = true)) &&
            (body == "전체" || item.bodyCategory == body) &&
            (equipment == "전체" || item.equipment == equipment) &&
            when (mode) {
                "좋아하는" -> item.favorite
                "자주하는" -> item.id in frequent
                "커스텀" -> item.id.startsWith("custom_")
                else -> true
            }
    }
    val searching = query.isNotBlank()
    val rows = buildList<LibRow> {
        filtered.groupBy { it.exercise.bodyCategory }
            .toList()
            .sortedBy { (_, entries) -> entries.first().bodyRank }
            .forEach { (bodyName, inBody) ->
                inBody.groupBy { it.sectionOrder to it.sectionTitle }
                    .toList()
                    .sortedBy { (key, _) -> key.first }
                    .forEach { (key, inSection) ->
                        add(LibRow.Header(if (body == "전체") "$bodyName · ${key.second}" else key.second, inSection.size))
                        // Stable sort: favorites first, otherwise the catalog's own order (basic grip before the variations).
                        val sorted = inSection.sortedByDescending { it.exercise.favorite }
                        val emitted = mutableSetOf<String>()
                        sorted.forEach { entry ->
                            val family = entry.exercise.family
                            val members = sorted.filter { it.exercise.family == family }
                            if (family.isNotEmpty() && members.size >= 2) {
                                if (emitted.add(family)) {
                                    val familyKey = "$bodyName/$family"
                                    val open = searching || expandedFamilies[familyKey] == true
                                    add(LibRow.Family(familyKey, family, members.size, open))
                                    if (open) members.forEach { add(LibRow.Item(it.exercise, indented = true)) }
                                }
                            } else {
                                add(LibRow.Item(entry.exercise, indented = false))
                            }
                        }
                    }
            }
    }

    Column(Modifier.fillMaxSize().background(KColors.Bg).padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 58.dp).pressable(actions.back), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Back, KColors.White, size = 28.dp)
            }
            Box(
                Modifier.weight(1f).height(58.dp).clip(KShape.Control).background(KColors.SurfaceRaised).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text("운동 검색", color = KColors.Muted, fontSize = 17.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = KColors.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(KColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(48.dp, 58.dp).pressable(actions.custom), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Plus, KColors.White, size = 28.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        TabStrip(Bodies, body) { body = it }
        Spacer(Modifier.height(10.dp))
        FilterStrip(Modes, mode) { mode = it }
        Spacer(Modifier.height(2.dp))
        FilterStrip(Equipments, equipment) { equipment = it }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "custom") {
                Row(Modifier.fillMaxWidth().pressable(actions.custom).padding(6.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KIcon(KIcon.Plus, KColors.Accent, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("커스텀 운동 추가", color = KColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text("맞는 운동이 없어요. 검색어나 필터를 바꿔 보세요.", color = KColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                }
            }
            items(rows, key = { row ->
                when (row) {
                    is LibRow.Header -> "h:${row.title}"
                    is LibRow.Family -> "f:${row.key}"
                    is LibRow.Item -> "i:${row.exercise.id}"
                }
            }) { row ->
                when (row) {
                    is LibRow.Header -> Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 6.dp)) {
                        Text(row.title, color = KColors.Muted, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("${row.count}", color = KColors.Faint, fontSize = 13.sp)
                    }
                    is LibRow.Family -> FamilyRow(row) { expandedFamilies[row.key] = !row.expanded }
                    is LibRow.Item -> ExerciseRow(row.exercise, row.indented, thumbnail) { actions.add(row.exercise) }
                }
            }
        }
    }
}

@Composable
private fun FamilyRow(row: LibRow.Family, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (row.expanded) 90f else 0f, Motion.ui(), label = "chevron")
    Row(
        Modifier.fillMaxWidth().pressable(onToggle).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) { KIcon(KIcon.Forward, KColors.Muted, Modifier.rotate(rotation), 20.dp) }
        Text(row.title, color = KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("${row.count}가지", color = KColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ExerciseRow(exercise: ExerciseDefinition, indented: Boolean, thumbnail: (ExerciseDefinition) -> View, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(start = if (indented) 30.dp else 4.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val entered = rememberEntered()
        val fade by animateFloatAsState(if (entered) 1f else 0f, Motion.ui(), label = "thumb")
        AndroidView(factory = { thumbnail(exercise) }, modifier = Modifier.size(56.dp).alpha(fade))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(exercise.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${exercise.muscles} · ${exercise.equipment}", color = KColors.Muted, fontSize = 12.sp)
        }
        if (exercise.favorite) KIcon(KIcon.Heart, KColors.Accent, size = 16.dp, filled = true)
    }
}

@Composable
private fun TabStrip(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).drawBehind {
        drawLine(KColors.Hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
    }) {
        values.forEach { value ->
            val isSelected = value == selected
            val text by animateColorAsState(if (isSelected) KColors.White else KColors.Muted, Motion.ui(), label = "tabText")
            val bar by animateColorAsState(if (isSelected) KColors.Accent else Color.Transparent, Motion.ui(), label = "tabBar")
            Column(Modifier.pressable { onSelect(value) }.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 9.dp))
                Box(Modifier.width(24.dp).height(2.dp).background(bar))
            }
        }
    }
}

@Composable
private fun FilterStrip(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        values.forEach { value ->
            val isSelected = value == selected
            val text by animateColorAsState(if (isSelected) KColors.Accent else KColors.Muted, Motion.ui(), label = "filterText")
            Text(
                value, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.pressable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
