package com.srrtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.srrtracker.data.Tag
import com.srrtracker.stats.PracticeStats
import com.srrtracker.ui.GoalRow
import com.srrtracker.ui.SessionCard
import com.srrtracker.ui.TrackerViewModel
import com.srrtracker.ui.UiState

internal val DiePalette = listOf(
    0xFFFF3B3B.toInt(),
    0xFF3B82F6.toInt(),
    0xFF22C55E.toInt(),
    0xFFF5C518.toInt(),
    0xFFF4F4F5.toInt(),
    0xFF111111.toInt(),
    0xFFA855F7.toInt(),
    0xFFF97316.toInt(),
    0xFFEC4899.toInt(),
    0xFF14B8A6.toInt()
)

@Composable
internal fun TwoDiceIcon(die1: Int, die2: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        DieFace(die1)
        DieFace(die2)
    }
}

@Composable
private fun DieFace(color: Int) {
    val face = Color(color)
    val pip = if (isLight(color)) Color(0xFF111111) else Color.White
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(face),
        contentAlignment = Alignment.Center
    ) {
        // Five pips, so a light or dark face still reads as a die.
        Box(Modifier.size(16.dp)) {
            listOf(
                Alignment.TopStart, Alignment.TopEnd,
                Alignment.Center,
                Alignment.BottomStart, Alignment.BottomEnd
            ).forEach { spot ->
                Box(
                    Modifier.align(spot).size(3.dp).clip(RoundedCornerShape(2.dp)).background(pip)
                )
            }
        }
    }
}

private fun isLight(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.3 * r + 0.6 * g + 0.1 * b > 180
}

@Composable
internal fun TagChip(tag: Tag?, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tag != null) TwoDiceIcon(tag.die1Color, tag.die2Color)
            Text(tag?.name ?: "No tag", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun SessionsScreen(vm: TrackerViewModel, state: UiState) {
    val shown = if (state.tagFilter == null) {
        state.sessions
    } else {
        state.sessions.filter { it.tagId == state.tagFilter }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.openTagManager() }) { Text("Tags", color = Ink, fontSize = 18.sp) }
            TextButton(onClick = { vm.closeSessions() }) { Text("Close", color = Ink, fontSize = 18.sp) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip("All", state.tagFilter == null) { vm.setTagFilter(null) }
            state.tags.forEach { tag ->
                FilterChip(tag.name, state.tagFilter == tag.id, tag) { vm.setTagFilter(tag.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.goalRows.isNotEmpty()) {
                item {
                    Text("Goals", color = Muted, fontSize = 14.sp)
                }
                items(state.goalRows, key = { "goal-${it.tagId}" }) { GoalLine(it) }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        "No sessions for this tag yet.",
                        color = Muted,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            } else {
                items(shown, key = { it.id }) { card -> SessionCardView(vm, card) }
            }
        }
    }
}

@Composable
private fun GoalLine(row: GoalRow) {
    val frac = (row.throws.toFloat() / row.goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoDiceIcon(row.die1, row.die2)
            Spacer(Modifier.width(8.dp))
            Text(row.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(row.ratio, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "${PracticeStats.formatCount(row.throws)} / ${PracticeStats.formatCount(row.goal)}",
            color = Muted,
            fontSize = 14.sp
        )
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A30))
        ) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).background(Good))
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, tag: Tag? = null, onClick: () -> Unit) {
    val bg = if (selected) Color.White else Color(0xFF2A2A30)
    val fg = if (selected) Color.Black else Ink
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(bg, RoundedCornerShape(20.dp))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tag != null) TwoDiceIcon(tag.die1Color, tag.die2Color)
            Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SessionCardView(vm: TrackerViewModel, card: SessionCard) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.die1 != null && card.die2 != null) {
                TwoDiceIcon(card.die1, card.die2)
                Spacer(Modifier.width(8.dp))
            }
            Text(card.tagName ?: "No tag", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (card.current) Text("open", color = Good, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(card.ratio, color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                PracticeStats.formatCount(card.throws),
                color = Ink,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text("${card.whenLabel} · ${card.durationLabel}", color = Muted, fontSize = 14.sp)
        Row {
            TextButton(onClick = { vm.openTagPicker(card.id) }) { Text("Tag", color = Ink) }
            TextButton(onClick = { vm.openSession(card.id) }) { Text("Open", color = Ink) }
            TextButton(onClick = { vm.deleteSession(card.id) }) { Text("Delete", color = Seven) }
        }
    }
}

@Composable
internal fun ManualEntryScreen(vm: TrackerViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enter rolls", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.closeManual() }) { Text("Close", color = Ink, fontSize = 18.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("No 7 Roll", { vm.quickEntry(false) }, Modifier.weight(1f), primary = false)
            BigButton("7", { vm.quickEntry(true) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        DiePicker("Left die", state.manualLeft) { vm.pickManual(0, it) }
        DiePicker("Right die", state.manualRight) { vm.pickManual(1, it) }
        val ready = state.manualLeft != null && state.manualRight != null
        BigButton("Save roll", { vm.saveManualEntry() }, Modifier.fillMaxWidth(), enabled = ready)
        BigButton(
            "Undo last roll",
            { vm.undoLast() },
            Modifier.fillMaxWidth(),
            primary = false,
            enabled = state.rows.isNotEmpty()
        )
        Spacer(Modifier.height(8.dp))
        if (state.rows.isEmpty()) {
            Text("No rolls in this session yet.", color = Muted, fontSize = 18.sp)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.rows, key = { it.id }) { row -> RollLine(row) { vm.openDetail(row.id) } }
            }
        }
    }
}

@Composable
internal fun TagManagerScreen(vm: TrackerViewModel, state: UiState) {
    var editing by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tags", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.closeTagManager() }) { Text("Close", color = Ink, fontSize = 18.sp) }
        }
        Text(
            "A tag is a throwing method. Set a goal and it shows on Sessions. Deleting a tag leaves those sessions untagged.",
            color = Muted,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tags, key = { it.id }) { tag ->
                Row(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TwoDiceIcon(tag.die1Color, tag.die2Color)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tag.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tag.goal?.let { "Goal ${PracticeStats.formatCount(it)}" } ?: "No goal",
                            color = Muted,
                            fontSize = 14.sp
                        )
                    }
                    TextButton(onClick = { editing = tag; creating = false }) { Text("Edit", color = Ink) }
                    TextButton(onClick = { vm.askDeleteTag(tag.id) }) { Text("Delete", color = Seven) }
                }
            }
            item {
                if (creating || editing != null) {
                    TagForm(
                        tag = editing,
                        onSave = { name, c1, c2, goal ->
                            vm.saveTag(editing?.id, name, c1, c2, goal)
                            editing = null
                            creating = false
                        },
                        onCancel = { editing = null; creating = false }
                    )
                } else {
                    BigButton("New tag", { creating = true }, Modifier.fillMaxWidth(), primary = false)
                }
            }
        }
    }
    val deleteId = state.deleteTagId
    if (deleteId != null) {
        val name = state.tags.find { it.id == deleteId }?.name ?: "this tag"
        AlertDialog(
            onDismissRequest = { vm.cancelDeleteTag() },
            title = { Text("Delete $name?") },
            text = { Text("Sessions that used it stay saved, without a tag. Their rolls are not deleted.") },
            confirmButton = { TextButton(onClick = { vm.confirmDeleteTag() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteTag() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TagForm(tag: Tag?, onSave: (String, Int, Int, Int?) -> Unit, onCancel: () -> Unit) {
    var name by remember(tag?.id) { mutableStateOf(tag?.name ?: "") }
    var die1 by remember(tag?.id) { mutableIntStateOf(tag?.die1Color ?: DiePalette[0]) }
    var die2 by remember(tag?.id) { mutableIntStateOf(tag?.die2Color ?: DiePalette[0]) }
    var goalText by remember(tag?.id) { mutableStateOf(tag?.goal?.toString() ?: "") }
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (tag == null) "New tag" else "Edit tag", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Left die", color = Muted, fontSize = 14.sp)
        ColorRow(die1) { die1 = it }
        Text("Right die", color = Muted, fontSize = 14.sp)
        ColorRow(die2) { die2 = it }
        OutlinedTextField(
            value = goalText,
            onValueChange = { goalText = it.filter { ch -> ch.isDigit() }.take(7) },
            label = { Text("Throw goal, optional") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TwoDiceIcon(die1, die2)
            Text(name.ifBlank { "Tag" }, color = Ink, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        BigButton(
            "Save tag",
            { onSave(name, die1, die2, goalText.toIntOrNull()) },
            Modifier.fillMaxWidth(),
            enabled = name.isNotBlank()
        )
        BigButton("Cancel", onCancel, Modifier.fillMaxWidth(), primary = false)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiePalette.forEach { color ->
            val mark = if (color == selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier
            TextButton(onClick = { onPick(color) }, modifier = mark) {
                DieFace(color)
            }
        }
    }
}

@Composable
internal fun TagPickerDialog(vm: TrackerViewModel, state: UiState) {
    val sessionId = state.tagPickerFor ?: return
    Dialog(onDismissRequest = { vm.closeTagPicker() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(Card, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tag this session", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            BigButton("No tag", { vm.setSessionTag(sessionId, null) }, Modifier.fillMaxWidth(), primary = false)
            state.tags.forEach { tag ->
                TextButton(onClick = { vm.setSessionTag(sessionId, tag.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TwoDiceIcon(tag.die1Color, tag.die2Color)
                        Spacer(Modifier.width(10.dp))
                        Text(tag.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            BigButton("Cancel", { vm.closeTagPicker() }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

@Composable
internal fun NewSessionDialog(vm: TrackerViewModel, state: UiState) {
    Dialog(onDismissRequest = { vm.cancelNewSession() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(Card, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Start a new session?", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
            Text("The rolls you have now stay saved. Pick a tag, or none.", color = Muted, fontSize = 16.sp)
            FilterChip("No tag", state.newSessionTagId == null) { vm.pickNewSessionTag(null) }
            state.tags.forEach { tag ->
                FilterChip(tag.name, state.newSessionTagId == tag.id, tag) { vm.pickNewSessionTag(tag.id) }
            }
            BigButton("New session", { vm.confirmNewSession() }, Modifier.fillMaxWidth())
            BigButton("Cancel", { vm.cancelNewSession() }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

@Composable
internal fun RestoreDialog(vm: TrackerViewModel, summary: String) {
    AlertDialog(
        onDismissRequest = { vm.cancelRestore() },
        title = { Text("Replace everything on this phone?") },
        text = {
            Text(
                "This file has $summary. A copy of what is on the phone now is saved in the app first, then this file replaces sessions, rolls, tags, and goals."
            )
        },
        confirmButton = { TextButton(onClick = { vm.confirmRestore() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("Cancel") } }
    )
}
