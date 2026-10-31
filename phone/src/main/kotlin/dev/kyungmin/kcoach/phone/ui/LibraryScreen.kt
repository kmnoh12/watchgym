package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kyungmin.kcoach.phone.ExerciseDefinition
import kotlin.math.floor
import kotlin.math.roundToInt

/** One exercise plus where it sits in the browse tree. */
class LibraryEntry(val exercise: ExerciseDefinition, val sectionOrder: Int, val sectionTitle: String, val bodyRank: Int, val body: String = exercise.bodyCategory)

class LibraryActions(
    val back: () -> Unit,
    val custom: () -> Unit,
    val add: (ExerciseDefinition) -> Unit,
)

/** The user's favourite ids and the tap that flips one. Left out where a heart makes no sense (calibration). */
class FavoriteUi(val ids: Set<String>, val toggle: (String) -> Unit)

private val Modes = listOf("전체", "좋아하는", "자주하는", "최근", "커스텀")
private val Bodies = listOf("전체") + dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyOrder
private val Equipments = listOf("전체", "맨몸", "덤벨", "바벨", "머신", "케이블", "기타")
private val SideGutter = 18.dp

private sealed interface LibRow {
    /** Body-part divider, shown only while every body part is listed. */
    data class Band(val body: String, val count: Int) : LibRow
    data class Header(val key: String, val title: String, val count: Int) : LibRow
    data class Family(
        val key: String,
        val title: String,
        val members: List<ExerciseDefinition>,
        val expanded: Boolean,
    ) : LibRow
    data class Item(val exercise: ExerciseDefinition) : LibRow
}

@Composable
fun LibraryScreen(
    catalog: List<LibraryEntry>,
    frequent: Set<String>,
    actions: LibraryActions,
    favorites: FavoriteUi? = null,
    thumbnail: (ExerciseDefinition) -> View,
) {
    ExerciseBrowser(catalog, frequent, actions, thumbnail, favorites = favorites)
}

/** Shared browse tree for adding an exercise and choosing a calibration capture. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseBrowser(
    catalog: List<LibraryEntry>,
    frequent: Set<String> = emptySet(),
    actions: LibraryActions,
    thumbnail: (ExerciseDefinition) -> View,
    calibration: Boolean = false,
    header: @Composable () -> Unit = {},
    enabled: (ExerciseDefinition) -> Boolean = { true },
    status: (ExerciseDefinition) -> String? = { null },
    onLongClick: ((ExerciseDefinition) -> Unit)? = null,
    favorites: FavoriteUi? = null,
) {
    var mode by remember { mutableStateOf("전체") }
    var body by remember { mutableStateOf("전체") }
    var equipment by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    val expandedFamilies = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    fun isFavorite(item: ExerciseDefinition) = if (favorites != null) item.id in favorites.ids else item.favorite

    val shown = remember(catalog) {
        catalog.flatMap { e ->
            dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodies(e.exercise.bodyCategory, e.exercise.pattern, e.exercise.id).map { b ->
                if (b == e.exercise.bodyCategory) e else {
                    val (order, title) = dev.kyungmin.kcoach.core.ExerciseTaxonomy.section(b, e.exercise.pattern)
                    LibraryEntry(e.exercise, order, title, dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyRank(b), b)
                }
            }
        }
    }
    val filtered = shown.filter { entry ->
        val item = entry.exercise
        (query.isBlank() || item.name.contains(query, ignoreCase = true)) &&
            (body == "전체" || entry.body == body) &&
            (equipment == "전체" || item.equipment == equipment) &&
            when (mode) {
                "좋아하는" -> isFavorite(item)
                "자주하는" -> item.id in frequent
                "커스텀" -> item.id.startsWith("custom_")
                else -> true
            }
    }
    val searching = query.isNotBlank()
    val rows = buildList<LibRow> {
        filtered.groupBy { it.body }
            .toList()
            .sortedBy { (_, entries) -> entries.first().bodyRank }
            .forEach { (bodyName, inBody) ->
                if (body == "전체") add(LibRow.Band(bodyName, inBody.size))
                inBody.groupBy { it.sectionOrder to it.sectionTitle }
                    .toList()
                    .sortedBy { (key, _) -> key.first }
                    .forEach { (key, inSection) ->
                        add(LibRow.Header("$bodyName/${key.second}", key.second, inSection.size))
                        // Stable sort: favorites first, otherwise the catalog's own order (basic grip before the variations).
                        val sorted = inSection.sortedByDescending { isFavorite(it.exercise) }
                        val emitted = mutableSetOf<String>()
                        sorted.forEach { entry ->
                            val family = entry.exercise.family
                            val members = sorted.filter { it.exercise.family == family }.map { it.exercise }
                            if (family.isNotEmpty() && members.size >= 2) {
                                if (emitted.add(family)) {
                                    val familyKey = "$bodyName/$family"
                                    val open = searching || expandedFamilies[familyKey] == true
                                    add(LibRow.Family(familyKey, family, members, open))
                                }
                            } else {
                                add(LibRow.Item(entry.exercise))
                            }
                        }
                    }
            }
    }

    // Lazy-list positions of the body bands, so the tab strip can follow the scroll.
    val firstRowIndex = (if (!calibration) 1 else 0) + (if (rows.isEmpty()) 1 else 0)
    val bands = rows.withIndex().filter { it.value is LibRow.Band }
        .map { (it.index + firstRowIndex) to Bodies.indexOf((it.value as LibRow.Band).body) }
    val spy: Float? by remember(bands) {
        derivedStateOf { if (body == "전체") scrollPosition(listState, bands) else null }
    }

    Column(Modifier.fillMaxSize().background(KColors.Bg).padding(top = 14.dp)) {
        Column(Modifier.padding(horizontal = SideGutter)) {
            header()
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!calibration) Box(Modifier.size(54.dp, 58.dp).pressable(actions.back), contentAlignment = Alignment.Center) {
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
                if (!calibration) Spacer(Modifier.width(12.dp))
                if (!calibration) Box(Modifier.size(48.dp, 58.dp).pressable(actions.custom), contentAlignment = Alignment.Center) {
                    KIcon(KIcon.Plus, KColors.White, size = 28.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            TabStrip(Bodies, body, spy) { body = it }
            Spacer(Modifier.height(10.dp))
            if (!calibration) FilterStrip(Modes, mode) { mode = it }
            Spacer(Modifier.height(2.dp))
            FilterStrip(Equipments, equipment) { equipment = it }
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 20.dp)) {
            if (!calibration) item(key = "custom") {
                Row(Modifier.fillMaxWidth().pressable(actions.custom).padding(SideGutter + 6.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KIcon(KIcon.Plus, KColors.Accent, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("커스텀 운동 추가", color = KColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text("맞는 운동이 없어요. 검색어나 필터를 바꿔 보세요.", color = KColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(SideGutter + 14.dp))
                }
            }
            items(rows, key = { row ->
                when (row) {
                    is LibRow.Band -> "b:${row.body}"
                    is LibRow.Header -> "h:${row.key}"
                    is LibRow.Family -> "f:${row.key}"
                    is LibRow.Item -> "i:${row.exercise.id}"
                }
            }) { row ->
                when (row) {
                    is LibRow.Band -> BodyBand(row)
                    is LibRow.Header -> Row(Modifier.fillMaxWidth().padding(start = SideGutter + 4.dp, top = 12.dp, bottom = 4.dp)) {
                        Text(row.title, color = KColors.Muted, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("${row.count}", color = KColors.Faint, fontSize = 13.sp)
                    }
                    is LibRow.Family -> FamilyCard(
                        row, thumbnail, favorites, ::isFavorite, enabled, status, onLongClick,
                        toggle = { expandedFamilies[row.key] = !row.expanded },
                        onAdd = actions.add,
                    )
                    is LibRow.Item -> ExerciseRow(
                        row.exercise, 56, thumbnail, enabled(row.exercise), status(row.exercise),
                        onLongClick?.let { action -> { action(row.exercise) } },
                        favorites?.let { isFavorite(row.exercise) to { it.toggle(row.exercise.id) } },
                        Modifier.padding(horizontal = SideGutter),
                    ) { actions.add(row.exercise) }
                }
            }
        }
    }
}

/**
 * Where the list is scrolled to, in tab-index units: the tab of the body part on screen, moving smoothly toward the
 * next tab while the next body band travels up through the top 45% of the list.
 */
private fun scrollPosition(state: LazyListState, bands: List<Pair<Int, Int>>): Float? {
    if (bands.isEmpty()) return null
    val first = state.firstVisibleItemIndex
    val current = bands.indexOfLast { it.first <= first }.coerceAtLeast(0)
    val here = bands[current].second.toFloat()
    val next = bands.getOrNull(current + 1) ?: return here
    val info = state.layoutInfo
    val shown = info.visibleItemsInfo.firstOrNull { it.index == next.first } ?: return here
    val zone = (info.viewportEndOffset - info.viewportStartOffset) * 0.45f
    if (zone <= 0f) return here
    val progress = ((zone - (shown.offset - info.viewportStartOffset)) / zone).coerceIn(0f, 1f)
    return here + (next.second - here) * progress
}

@Composable
private fun BodyBand(row: LibRow.Band) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).background(KColors.Surface)
            .drawBehind {
                drawLine(KColors.SurfaceRaised, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
                drawLine(KColors.SurfaceRaised, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            }
            .padding(horizontal = SideGutter + 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.body, color = KColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("${row.count}", color = KColors.Muted, fontSize = 13.sp)
    }
}

/** Short variant names: each member's name minus the words its group title already says ("플랫 · 인클라인 외 3"). */
private fun variantPreview(title: String, members: List<ExerciseDefinition>): String {
    val shared = title.split(' ', '·').filter { it.isNotBlank() }
    val names = members.map { member ->
        member.name.replace(Regex("\\(.*?\\)"), "").split(' ')
            .filter { word -> word.isNotBlank() && shared.none { word.contains(it) } }
            .joinToString(" ").ifBlank { "기본" }
    }.distinct()
    return names.take(3).joinToString(" · ") + if (names.size > 3) " 외 ${names.size - 3}" else ""
}

/** A group of variants: a plain row with overlapping pictures while closed, a card with its members once opened. */
@Composable
private fun FamilyCard(
    row: LibRow.Family,
    thumbnail: (ExerciseDefinition) -> View,
    favorites: FavoriteUi?,
    isFavorite: (ExerciseDefinition) -> Boolean,
    enabled: (ExerciseDefinition) -> Boolean,
    status: (ExerciseDefinition) -> String?,
    onLongClick: ((ExerciseDefinition) -> Unit)?,
    toggle: () -> Unit,
    onAdd: (ExerciseDefinition) -> Unit,
) {
    val chevron by animateFloatAsState(if (row.expanded) 180f else 0f, Motion.ui(), label = "chevron")
    val backdrop = if (row.expanded) KColors.SurfaceRaised else KColors.Bg
    val preview = variantPreview(row.title, row.members)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SideGutter, vertical = if (row.expanded) 6.dp else 0.dp)
            .clip(RoundedCornerShape(12.dp)).background(backdrop),
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(toggle).padding(horizontal = if (row.expanded) 10.dp else 0.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 72.dp, height = 52.dp)) {
                row.members.take(3).forEachIndexed { index, member ->
                    AndroidView(
                        factory = { thumbnail(member) },
                        modifier = Modifier.offset(x = (index * 14).dp).size(width = 40.dp, height = 52.dp)
                            .clip(RoundedCornerShape(9.dp)).border(1.5.dp, backdrop, RoundedCornerShape(9.dp)),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(preview, color = KColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${row.members.size}", color = KColors.Soft, fontSize = 12.sp)
                Box(Modifier.graphicsLayer { rotationZ = chevron }) { KIcon(KIcon.Forward, KColors.Soft, Modifier.graphicsLayer { rotationZ = 90f }, 18.dp) }
            }
        }
        if (row.expanded) Row(Modifier.height(IntrinsicSize.Min).padding(start = 22.dp, bottom = 6.dp)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(KColors.Accent))
            Column(Modifier.weight(1f)) {
                row.members.forEach { member ->
                    ExerciseRow(
                        member, 44, thumbnail, enabled(member), status(member),
                        onLongClick?.let { action -> { action(member) } },
                        favorites?.let { isFavorite(member) to { it.toggle(member.id) } },
                        Modifier.padding(start = 8.dp, end = 4.dp),
                    ) { onAdd(member) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseRow(
    exercise: ExerciseDefinition,
    thumbSize: Int,
    thumbnail: (ExerciseDefinition) -> View,
    enabled: Boolean,
    status: String?,
    onLongClick: (() -> Unit)?,
    favorite: Pair<Boolean, () -> Unit>?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
            .then(if (onLongClick != null) Modifier.combinedClickable(
                onClick = { if (enabled) onClick() }, onLongClick = onLongClick,
            ) else Modifier.pressable { if (enabled) onClick() })
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val entered = rememberEntered()
        val fade by animateFloatAsState(if (entered) 1f else 0f, Motion.ui(), label = "thumb")
        AndroidView(factory = { thumbnail(exercise) }, modifier = Modifier.size(thumbSize.dp).alpha(fade))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(exercise.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${exercise.muscles} · ${exercise.equipment}", color = KColors.Muted, fontSize = 12.sp)
            status?.let { Text(it, color = if (enabled) KColors.Accent else KColors.Muted, fontSize = 12.sp) }
        }
        if (favorite != null) FavoriteHeart(favorite.first, favorite.second)
    }
}

/** Sage outline by default; a tap fills it and gives it a short bounce. */
@Composable
private fun FavoriteHeart(on: Boolean, onToggle: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val fill by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "heartFill")
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (!settled) { settled = true; return@LaunchedEffect }
        if (on) {
            scale.snapTo(0.8f)
            scale.animateTo(1.42f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f))
        } else {
            scale.animateTo(0.85f, tween(80))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
        }
    }
    Box(
        Modifier.size(44.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            KIcon(KIcon.Heart, KColors.Accent, size = 24.dp)
            KIcon(KIcon.Heart, KColors.Accent.copy(alpha = fill), size = 24.dp, filled = true)
        }
    }
}

/** Tabs filter the list. While every body part is listed, a bone line also follows the scroll position between tabs. */
@Composable
private fun TabStrip(values: List<String>, selected: String, spy: Float?, onSelect: (String) -> Unit) {
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val half = with(LocalDensity.current) { 12.dp.toPx() }
    val margin = with(LocalDensity.current) { 48.dp.toPx() }
    val current = spy?.roundToInt()
    LaunchedEffect(current, bounds[current]) {
        val left = bounds[current ?: return@LaunchedEffect]?.first ?: return@LaunchedEffect
        scroll.animateScrollTo((left - margin).toInt().coerceAtLeast(0))
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll).drawBehind {
        drawLine(KColors.Hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
    }) {
        Row {
            values.forEachIndexed { index, value ->
                val isSelected = value == selected
                val text by animateColorAsState(if (isSelected) KColors.White else KColors.Muted, Motion.ui(), label = "tabText")
                val bar by animateColorAsState(if (isSelected) KColors.Accent else Color.Transparent, Motion.ui(), label = "tabBar")
                Column(
                    Modifier.onGloballyPositioned { bounds[index] = it.positionInParent().x to (it.positionInParent().x + it.size.width) }
                        .pressable { onSelect(value) }.padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(value, color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 9.dp))
                    Box(Modifier.width(24.dp).height(2.dp).background(bar))
                }
            }
        }
        if (spy != null) {
            val index = floor(spy).toInt().coerceIn(0, values.lastIndex)
            val from = bounds[index]
            val to = bounds[(index + 1).coerceAtMost(values.lastIndex)]
            if (from != null && to != null) {
                val f = (spy - index).coerceIn(0f, 1f)
                val centerFrom = (from.first + from.second) / 2f
                val centerTo = (to.first + to.second) / 2f
                // The leading end runs ahead and the trailing end catches up, so the line stretches between tabs.
                val right = centerFrom + half + (centerTo - centerFrom) * (1f - (1f - f) * (1f - f) * (1f - f))
                val left = centerFrom - half + (centerTo - centerFrom) * (f * f * f)
                Box(
                    Modifier.align(Alignment.BottomStart).offset { IntOffset(left.roundToInt(), 0) }
                        .layout { measurable, constraints ->
                            val width = (right - left).roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .height(2.dp).background(KColors.Bone),
                )
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
