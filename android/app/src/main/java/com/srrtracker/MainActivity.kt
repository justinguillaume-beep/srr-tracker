package com.srrtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srrtracker.camera.AutoCapture
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.decodePips
import com.srrtracker.ui.PendingCheck
import com.srrtracker.ui.RollRow
import com.srrtracker.ui.SessionSummary
import com.srrtracker.ui.TrackerViewModel
import com.srrtracker.ui.UiEffect
import com.srrtracker.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: TrackerViewModel = viewModel()
            val state by vm.ui.collectAsState()
            val context = LocalContext.current
            LaunchedEffect(vm) {
                vm.effects.collect { effect ->
                    when (effect) {
                        is UiEffect.ShareFile -> shareCsv(context, File(effect.path))
                        is UiEffect.ShareImage -> shareImage(context, File(effect.path))
                    }
                }
            }
            val view = LocalView.current
            DisposableEffect(state.running) {
                val window = (view.context as? ComponentActivity)?.window
                if (state.running) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
                when {
                    !state.guideDone -> GuideScreen(vm, state)
                    state.detailId != null -> DetailScreen(vm, state)
                    else -> MainScreen(vm, state)
                }
                state.pending?.let { CheckScreen(vm, it, state.notice) }
            }
        }
    }
}

private val Bg = Color(0xFF0B0B0D)
private val Card = Color(0xFF16161A)
private val Muted = Color(0xFFA1A1AA)
private val Seven = Color(0xFFFF3B3B)
private val Good = Color(0xFF3DDC84)
private val Ink = Color(0xFFF4F4F5)

@Composable
private fun GuideScreen(vm: TrackerViewModel, state: UiState) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) step = 2
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Step ${step + 1} of 3", color = Muted, fontSize = 16.sp)
        when (step) {
            0 -> {
                Text("Count your dice", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(
                    "This phone watches two dice and writes down each roll. You see how many rolls you have thrown, and how often a 7 comes up.",
                    color = Ink, fontSize = 22.sp
                )
                Spacer(Modifier.weight(1f))
                BigButton("Next", { step = 1 }, Modifier.fillMaxWidth())
            }
            1 -> {
                Text("Allow the camera", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(
                    "The camera looks down at the table. Photos stay on this phone. Nothing is sent anywhere.",
                    color = Ink, fontSize = 22.sp
                )
                Spacer(Modifier.weight(1f))
                if (!granted) {
                    BigButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                } else {
                    BigButton("Next", { step = 2 }, Modifier.fillMaxWidth())
                }
            }
            else -> {
                Text("Point the phone down", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Lay the phone face-down over the spot where the dice stop. The dice should sit inside the box.",
                    color = Ink, fontSize = 20.sp
                )
                if (granted) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).heightIn(min = 180.dp)
                    ) {
                        CameraPane(vm, running = false, modifier = Modifier.fillMaxSize())
                        FramingOverlay(state.diceInBox, state.frame, vm::setFrame, Modifier.fillMaxSize())
                    }
                } else {
                    Text("The camera is off. Go back and tap Allow camera.", color = Seven, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    if (state.diceInBox) "Dice in the box" else "No dice in the box yet",
                    color = if (state.diceInBox) Good else Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                BigButton("Start", { vm.completeGuide() }, Modifier.fillMaxWidth(), enabled = granted)
            }
        }
    }
}

@Composable
private fun MainScreen(vm: TrackerViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.sessionName, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.openMenu() }) {
                Text("Menu", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${state.rollCount}", color = Ink, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("rolls", color = Muted, fontSize = 16.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(state.ratio, color = Ink, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("rolls per 7", color = Muted, fontSize = 16.sp)
                Text(state.pctLabel, color = if (state.sevens > 0) Ink else Muted, fontSize = 16.sp)
            }
        }
        Text(
            state.status,
            color = statusColor(state),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        Box(
            Modifier.fillMaxWidth().height(210.dp).background(Color.Black, RoundedCornerShape(16.dp))
        ) {
            CameraPane(
                vm,
                running = state.running,
                modifier = Modifier.fillMaxSize()
            )
            FramingOverlay(state.diceInBox, state.frame, vm::setFrame, Modifier.fillMaxSize())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.nudgeZoom(-0.15f) }) { Text("−", color = Ink, fontSize = 28.sp) }
            Slider(
                value = state.zoom.coerceIn(state.zoomMin, state.zoomMax.coerceAtLeast(state.zoomMin)),
                onValueChange = { vm.setZoom(it) },
                valueRange = state.zoomMin..state.zoomMax.coerceAtLeast(state.zoomMin + 0.01f),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.nudgeZoom(0.15f) }) { Text("+", color = Ink, fontSize = 28.sp) }
        }
        if (state.diePx != null && state.diePx < 60) {
            Text(
                "Dice look small (${state.diePx} px): zoom in or move the phone closer.",
                color = Seven,
                fontSize = 16.sp
            )
            BigButton("Auto-zoom", { vm.autoZoom() }, Modifier.fillMaxWidth(), primary = false)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(
                if (state.running) "Pause" else "Start",
                { vm.setRunning(!state.running) },
                Modifier.weight(1f)
            )
            BigButton(
                "Undo last roll",
                { vm.undoLast() },
                Modifier.weight(1f),
                primary = false,
                enabled = state.rows.isNotEmpty()
            )
        }
        Spacer(Modifier.height(8.dp))
        BigButton(
            "Count now",
            { vm.countNow() },
            Modifier.fillMaxWidth(),
            primary = false,
            enabled = state.running && !state.busy && state.pending == null
        )
        Spacer(Modifier.height(8.dp))
        if (state.rows.isEmpty()) {
            Text(
                "No rolls yet. Put the dice in the box and hold still, or tap Count now.",
                color = Muted,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            Text("Newest first. Tap a roll to see the photo.", color = Muted, fontSize = 14.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(state.rows, key = { it.id }) { row -> RollLine(row) { vm.openDetail(row.id) } }
            }
        }
    }
    if (state.showMenu) MenuDialog(vm)
    if (state.showSettings) SettingsDialog(vm, state)
    if (state.showSessions) SessionsDialog(vm, state.sessions)
    if (state.confirmNew) {
        AlertDialog(
            onDismissRequest = { vm.cancelNewSession() },
            title = { Text("Start a new session?") },
            text = { Text("The rolls you have now stay saved under Past sessions.") },
            confirmButton = { TextButton(onClick = { vm.confirmNewSession() }) { Text("New session") } },
            dismissButton = { TextButton(onClick = { vm.cancelNewSession() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RollLine(row: RollRow, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("#${row.number}", color = Muted, fontSize = 16.sp, modifier = Modifier.width(52.dp))
            Text(
                if (row.unread) "unread" else "${row.total}",
                color = if (row.isSeven) Seven else if (row.unread) Color(0xFFFFC107) else Ink,
                fontSize = if (row.unread) 22.sp else 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(row.ratio, color = Ink, fontSize = 22.sp)
        }
    }
}

@Composable
private fun CheckScreen(vm: TrackerViewModel, pending: PendingCheck, notice: String?) {
    var photo by remember(pending.jpeg) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pending.jpeg) {
        photo = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(pending.jpeg, 0, pending.jpeg.size)
        }
    }
    val dice = pending.detected?.dice.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (pending.detected?.ok == true) "Check the dice" else "Could not read the dice",
            color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold
        )
        Text(
            pending.reason.ifBlank { "Tap a number if it is wrong, then save." },
            color = Muted,
            fontSize = 18.sp
        )
        Text("Yellow boxes are where the reader looked on this photo.", color = Muted, fontSize = 16.sp)
        photo?.let { bmp ->
            StillWithBoxes(bmp, dice)
            if (dice.isEmpty()) {
                Text("No die box on this photo.", color = Muted, fontSize = 16.sp)
            } else {
                Text("Each die, enlarged from this still.", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                dice.forEachIndexed { index, die ->
                    val crop = remember(bmp, die.x, die.y, die.w, die.h) { dieCrop(bmp, die) }
                    Text(
                        "Die ${index + 1}: box ${die.w}×${die.h} px, long side ${max(die.w, die.h)} px, read ${die.count}",
                        color = Muted,
                        fontSize = 16.sp
                    )
                    Image(
                        crop.asImageBitmap(),
                        contentDescription = "Die ${index + 1}",
                        modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        if (!notice.isNullOrBlank()) Text(notice, color = Good, fontSize = 16.sp)
        DiePicker("Left die", pending.d1) { vm.pickPending(0, it) }
        DiePicker("Right die", pending.d2) { vm.pickPending(1, it) }
        val total = if (pending.d1 != null && pending.d2 != null) pending.d1 + pending.d2 else null
        Text(
            total?.toString() ?: "?",
            color = if (total == 7) Seven else Ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        BigButton("Share last photo", { vm.sharePending() }, Modifier.fillMaxWidth(), primary = false)
        BigButton("Save full-resolution still", { vm.savePendingToDownloads() }, Modifier.fillMaxWidth(), primary = false)
        BigButton("Save roll", { vm.savePending() }, Modifier.fillMaxWidth(), enabled = total != null)
        BigButton("Skip", { vm.skipPending() }, Modifier.fillMaxWidth(), primary = false)
    }
}

@Composable
private fun StillWithBoxes(bmp: Bitmap, dice: List<com.srrtracker.detect.DiceDetector.DieMark>) {
    Canvas(Modifier.fillMaxWidth().height(240.dp).background(Color.Black)) {
        val scale = min(size.width / bmp.width, size.height / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (size.width - dw) / 2f
        val top = (size.height - dh) / 2f
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1))
        )
        for (die in dice) {
            drawRect(
                color = Color(0xFFFFD400),
                topLeft = Offset(left + die.x * scale, top + die.y * scale),
                size = Size(die.w * scale, die.h * scale),
                style = Stroke(width = 3f)
            )
        }
    }
}

private fun dieCrop(src: Bitmap, die: com.srrtracker.detect.DiceDetector.DieMark): Bitmap {
    val pad = (max(die.w, die.h) * 0.25f).toInt()
    val x = (die.x - pad).coerceIn(0, src.width - 1)
    val y = (die.y - pad).coerceIn(0, src.height - 1)
    val w = (die.w + pad * 2).coerceIn(1, src.width - x)
    val h = (die.h + pad * 2).coerceIn(1, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

@Composable
private fun DetailScreen(vm: TrackerViewModel, state: UiState) {
    val roll = vm.detailRoll()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                roll?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.ts)) } ?: "Roll",
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.closeDetail() }) { Text("Close", color = Ink, fontSize = 18.sp) }
        }
        if (roll == null) {
            Text("That roll is gone.", color = Muted, fontSize = 20.sp)
            return
        }
        var photo by remember(roll.photoPath) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(roll.photoPath) {
            val path = roll.photoPath
            photo = withContext(Dispatchers.IO) { if (path != null) BitmapFactory.decodeFile(path) else null }
        }
        val bmp = photo
        if (bmp == null) {
            Text("No photo for this roll.", color = Muted, fontSize = 18.sp)
        } else {
            val pips = remember(roll.pipsJson) { decodePips(roll.pipsJson) }
            Canvas(
                Modifier.fillMaxWidth().height(240.dp)
            ) {
                val scale = min(size.width / bmp.width, size.height / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (size.width - dw) / 2f
                val top = (size.height - dh) / 2f
                drawImage(
                    bmp.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1))
                )
                val maxDim = maxOf(dw, dh)
                for (p in pips) {
                    drawCircle(
                        color = if (p.die == 0) Color(0xFF7CDBFF) else Color(0xFFFFD400),
                        radius = maxOf(5f, (p.r * maxDim).toFloat()),
                        center = Offset(left + (p.x * dw).toFloat(), top + (p.y * dh).toFloat()),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }
        if (roll.unread || !roll.readReason.isNullOrBlank()) {
            Text(roll.readReason ?: "Could not read the dice.", color = Color(0xFFFFC107), fontSize = 18.sp)
        }
        Text("Tap a number to fix a die.", color = Muted, fontSize = 16.sp)
        DiePicker("Left die", roll.d1) { vm.correctDetail(0, it) }
        DiePicker("Right die", roll.d2) { vm.correctDetail(1, it) }
        Text(
            "${roll.total}",
            color = if (roll.total == 7) Seven else Ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        if (!state.notice.isNullOrBlank()) Text(state.notice, color = Good, fontSize = 16.sp)
        BigButton("Share last photo", { vm.shareDetailPhoto() }, Modifier.fillMaxWidth(), primary = false, enabled = roll.photoPath != null)
        BigButton("Save full-resolution still", { vm.saveDetailToDownloads() }, Modifier.fillMaxWidth(), primary = false, enabled = roll.photoPath != null)
        BigButton("Delete this roll", { vm.askDelete() }, Modifier.fillMaxWidth(), primary = false)
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            title = { Text("Delete this roll?") },
            confirmButton = { TextButton(onClick = { vm.confirmDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MenuDialog(vm: TrackerViewModel) {
    Dialog(onDismissRequest = { vm.closeMenu() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).background(Card, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Menu", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            BigButton("New session", { vm.askNewSession() }, Modifier.fillMaxWidth())
            BigButton("Past sessions", { vm.openSessions() }, Modifier.fillMaxWidth(), primary = false)
            BigButton("Save spreadsheet", { vm.exportCsv(); vm.closeMenu() }, Modifier.fillMaxWidth(), primary = false)
            BigButton("Settings", { vm.openSettings() }, Modifier.fillMaxWidth(), primary = false)
            BigButton("How to set up", { vm.reopenGuide() }, Modifier.fillMaxWidth(), primary = false)
            BigButton("Close", { vm.closeMenu() }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

@Composable
private fun SettingsDialog(vm: TrackerViewModel, state: UiState) {
    Dialog(onDismissRequest = { vm.closeSettings() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).background(Card, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Settings", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("How easily a throw is noticed", color = Ink, fontSize = 18.sp)
            Slider(
                value = state.sensitivity.toFloat(),
                onValueChange = { vm.setSensitivity(it.toInt()) },
                valueRange = 1f..100f
            )
            Text(
                when {
                    state.sensitivity < 35 -> "Low — ignores small bumps"
                    state.sensitivity > 70 -> "High — catches a light movement"
                    else -> "Medium"
                },
                color = Muted, fontSize = 16.sp
            )
            Text("How long the dice must sit still", color = Ink, fontSize = 18.sp)
            Slider(
                value = state.settleMs / 1000f,
                onValueChange = { vm.setSettleMs((it * 1000f).toLong()) },
                valueRange = 0.3f..1.5f,
                steps = 5
            )
            Text(String.format("%.1f seconds", state.settleMs / 1000f), color = Muted, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sounds", color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.soundOn, onCheckedChange = { vm.setSound(it) })
            }
            Text(
                "On by default. A short beep for a clear roll, a low buzz when the total is 7, and two descending tones when it cannot tell. A 7 does not also beep.",
                color = Muted,
                fontSize = 16.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save a marked photo", color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.markPhotos, onCheckedChange = { vm.setMarkPhotos(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save each die crop", color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.saveDieCrops, onCheckedChange = { vm.setSaveDieCrops(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save every capture, even failed", color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.saveAllCaptures, onCheckedChange = { vm.setSaveAllCaptures(it) })
            }
            Text(
                "On by default. Each still is saved at full resolution in Downloads/SRR-Tracker.",
                color = Muted,
                fontSize = 16.sp
            )
            Text("Drag the box onto the two dice. Drag a corner to resize it.", color = Muted, fontSize = 16.sp)
            BigButton("Reset box", { vm.resetFrame() }, Modifier.fillMaxWidth(), primary = false)
            BigButton("Done", { vm.closeSettings() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SessionsDialog(vm: TrackerViewModel, sessions: List<SessionSummary>) {
    Dialog(onDismissRequest = { vm.closeSessions() }) {
        Column(
            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp).heightIn(max = 520.dp)
        ) {
            Text("Past sessions", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(sessions, key = { it.id }) { session ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(session.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${session.rolls} rolls · ${session.ratio}" + if (session.current) " · open" else "",
                            color = Muted, fontSize = 14.sp
                        )
                        Row {
                            TextButton(onClick = { vm.openSession(session.id) }) { Text("Open") }
                            TextButton(onClick = { vm.deleteSession(session.id) }) { Text("Delete", color = Seven) }
                        }
                    }
                }
            }
            BigButton("Close", { vm.closeSessions() }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

@Composable
private fun CameraPane(vm: TrackerViewModel, running: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val state by vm.ui.collectAsState()
    val capture = remember(vm) {
        AutoCapture(
            context = context,
            onFrame = vm::onFrame,
            onStill = vm::onCaptured,
            onReady = vm::onCameraReady,
            onError = vm::onCameraError,
            onZoomRange = vm::onZoomRange
        )
    }
    SideEffect {
        capture.gate.sensitivity = state.sensitivity
        capture.gate.settleMs = state.settleMs
        capture.gate.running = running
        capture.gate.frame = state.frame
        if (capture.zoomRatio != state.zoom) capture.zoomRatio = state.zoom
    }
    LaunchedEffect(state.manualCapture) {
        if (state.manualCapture) {
            capture.requestCapture()
            vm.acknowledgeManualCapture()
        }
    }
    DisposableEffect(capture) {
        onDispose { capture.stop() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view ->
            if (view.tag == "bound") return@AndroidView
            view.doOnLayout {
                if (view.tag == "bound") return@doOnLayout
                view.tag = "bound"
                capture.start(view, lifecycle)
            }
        }
    )
}

@Composable
private fun FramingOverlay(
    diceInBox: Boolean,
    frame: NormRect,
    onFrame: (NormRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (diceInBox) Good else Color.White
    val latest = rememberUpdatedState(frame)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val cur = latest.value
                    val l = cur.left * w
                    val t = cur.top * h
                    val r = cur.right * w
                    val b = cur.bottom * h
                    val x = change.position.x
                    val y = change.position.y
                    val edge = 48f
                    val nearL = kotlin.math.abs(x - l) <= edge
                    val nearR = kotlin.math.abs(x - r) <= edge
                    val nearT = kotlin.math.abs(y - t) <= edge
                    val nearB = kotlin.math.abs(y - b) <= edge
                    var nl = l
                    var nt = t
                    var nr = r
                    var nb = b
                    val inside = x in l..r && y in t..b
                    if (!inside && !nearL && !nearR && !nearT && !nearB) return@detectDragGestures
                    if (inside && !(nearL || nearR || nearT || nearB)) {
                        nl += drag.x
                        nr += drag.x
                        nt += drag.y
                        nb += drag.y
                    } else {
                        if (nearL) nl += drag.x
                        if (nearR) nr += drag.x
                        if (nearT) nt += drag.y
                        if (nearB) nb += drag.y
                    }
                    val minW = w * 0.12f
                    val minH = h * 0.12f
                    if (nr - nl < minW) {
                        if (nearL && !nearR) nl = nr - minW else nr = nl + minW
                    }
                    if (nb - nt < minH) {
                        if (nearT && !nearB) nt = nb - minH else nb = nt + minH
                    }
                    onFrame(
                        NormRect(
                            left = (nl / w).coerceIn(0f, 0.88f),
                            top = (nt / h).coerceIn(0f, 0.88f),
                            right = (nr / w).coerceIn(0.12f, 1f),
                            bottom = (nb / h).coerceIn(0.12f, 1f)
                        )
                    )
                }
            }
        ) {
            val l = size.width * frame.left
            val t = size.height * frame.top
            val r = size.width * frame.right
            val b = size.height * frame.bottom
            drawRoundRect(
                color = color,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                cornerRadius = CornerRadius(24f, 24f),
                style = Stroke(width = 5f)
            )
            val handle = 14f
            for (c in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                drawCircle(color = color, radius = handle, center = c)
            }
        }
        Text(
            if (diceInBox) "Dice in the box" else "Drag the box onto the dice",
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun DiePicker(label: String, selected: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (n in 1..6) {
                val on = selected == n
                Button(
                    onClick = { onPick(n) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) Color.White else Color(0xFF2A2A30),
                        contentColor = if (on) Color.Black else Color.White
                    )
                ) {
                    Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color.White else Color(0xFF2A2A30),
            contentColor = if (primary) Color.Black else Color.White,
            disabledContainerColor = Color(0xFF2A2A30),
            disabledContentColor = Color(0xFF6B6B73)
        )
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

private fun statusColor(state: UiState): Color = when {
    state.statusIsSeven -> Seven
    state.status.startsWith("Logged") -> Good
    state.status.startsWith("Could not") || state.status.startsWith("Check") || state.status.startsWith("Camera") -> Color(0xFFFFC107)
    else -> Ink
}

private fun shareImage(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share the still"))
}

private fun shareCsv(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Save spreadsheet"))
}
