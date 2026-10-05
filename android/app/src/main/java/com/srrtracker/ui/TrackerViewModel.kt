package com.srrtracker.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srrtracker.camera.StillCapture
import com.srrtracker.camera.rgbToJpeg
import com.srrtracker.data.AppDatabase
import com.srrtracker.data.PhotoStore
import com.srrtracker.data.RollEntity
import com.srrtracker.data.Session
import com.srrtracker.detect.CameraBox
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.DebugMarks
import com.srrtracker.detect.DiceDetector
import com.srrtracker.detect.FrameTarget
import com.srrtracker.detect.ImageOps
import com.srrtracker.detect.MotionGate
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.RgbImage
import com.srrtracker.detect.encodePips
import com.srrtracker.feedback.RollFeedback
import com.srrtracker.stats.SrrStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class RollRow(
    val id: Long,
    val number: Int,
    val total: Int,
    val ratio: String,
    val isSeven: Boolean,
    val unread: Boolean = false,
    val reason: String? = null
)

data class PendingCheck(
    val jpeg: ByteArray,
    val d1: Int?,
    val d2: Int?,
    val detected: DiceDetector.Detection?,
    val reason: String,
    val rollId: Long = 0
)

data class SessionSummary(
    val id: Long,
    val name: String,
    val rolls: Int,
    val ratio: String,
    val current: Boolean
)

data class UiState(
    val ready: Boolean = false,
    val guideDone: Boolean = false,
    val running: Boolean = false,
    val status: String = "Starting camera...",
    val statusIsSeven: Boolean = false,
    val rollCount: Int = 0,
    val sevens: Int = 0,
    val ratio: String = "\u2014",
    val pctLabel: String = "no 7s yet",
    val rows: List<RollRow> = emptyList(),
    val diceInBox: Boolean = false,
    val pending: PendingCheck? = null,
    val detailId: Long? = null,
    val showMenu: Boolean = false,
    val showSettings: Boolean = false,
    val showSessions: Boolean = false,
    val confirmNew: Boolean = false,
    val confirmDelete: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val sensitivity: Int = 50,
    val settleMs: Long = 500L,
    val soundOn: Boolean = true,
    val markPhotos: Boolean = true,
    val saveDieCrops: Boolean = true,
    val sessionName: String = "",
    val cameraMessage: String? = null,
    val busy: Boolean = false,
    val manualCapture: Boolean = false,
    val frame: NormRect = CameraBox.asRect(),
    val zoom: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 8f,
    val diePx: Int? = null,
    val saveAllCaptures: Boolean = true,
    val notice: String? = null
)

sealed interface UiEffect {
    data class ShareFile(val path: String) : UiEffect
    data class ShareImage(val path: String) : UiEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val photos = PhotoStore(app)
    private val prefs = app.getSharedPreferences("srr", Application.MODE_PRIVATE)
    private val feedback = RollFeedback(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<UiEffect> = _effects

    private val sessionId = MutableStateFlow(0L)
    private val counting = AtomicBoolean(false)
    private var currentRolls: List<RollEntity> = emptyList()
    private var lastInfo: MotionGate.FrameInfo? = null
    private var flashJob: Job? = null

    init {
        val guide = prefs.getBoolean(KEY_GUIDE, false)
        val sensitivity = prefs.getInt(KEY_SENS, 50)
        val settle = prefs.getLong(KEY_SETTLE, 500L)
        val sound = prefs.getBoolean(KEY_SOUNDS, true)
        val mark = prefs.getBoolean(KEY_MARK, true)
        val crops = prefs.getBoolean(KEY_CROPS, true)
        val saveAll = prefs.getBoolean(KEY_SAVE_ALL, true)
        val zoom = prefs.getFloat(KEY_ZOOM, 1f)
        val frame = loadFrame()
        _ui.update {
            it.copy(
                guideDone = guide,
                sensitivity = sensitivity,
                settleMs = settle,
                soundOn = sound,
                markPhotos = mark,
                saveDieCrops = crops,
                saveAllCaptures = saveAll,
                frame = frame,
                zoom = zoom,
                status = if (guide) "Paused" else "Starting camera..."
            )
        }
        viewModelScope.launch {
            val saved = prefs.getLong(KEY_SESSION, -1L)
            val existing = if (saved > 0) db.sessions().get(saved) else null
            val session = existing ?: insertSession()
            sessionId.value = session.id
            _ui.update { it.copy(ready = true, sessionName = session.name) }
        }
        viewModelScope.launch {
            sessionId.flatMapLatest { id ->
                if (id <= 0) flowOf(emptyList()) else db.rolls().observe(id)
            }.collect { applyRolls(it) }
        }
    }

    fun onFrame(info: MotionGate.FrameInfo) {
        val prev = lastInfo
        lastInfo = info
        val changed = prev?.diceInBox != info.diceInBox || prev.phase != info.phase || prev.stage != info.stage
        if (changed) {
            Log.i(TAG, "gate ${info.stage} phase=${info.phase} dice=${info.diceInBox} capture=${info.shouldCapture} ${info.detail}")
        }
        if (info.shouldCapture) {
            _ui.update {
                it.copy(diceInBox = info.diceInBox, status = "Capturing...", statusIsSeven = false, busy = true)
            }
        }
        if (!changed && _ui.value.cameraMessage == null) return
        _ui.update { it.copy(diceInBox = info.diceInBox) }
        if (counting.get() || _ui.value.pending != null || _ui.value.busy) return
        if (flashJob?.isActive == true) return
        publishLiveStatus()
    }

    fun countNow() {
        val state = _ui.value
        if (!state.running || state.pending != null || state.busy || counting.get()) return
        Log.i(TAG, "count now")
        _ui.update {
            it.copy(status = "Capturing...", statusIsSeven = false, busy = true, manualCapture = true)
        }
    }

    fun acknowledgeManualCapture() {
        _ui.update { it.copy(manualCapture = false) }
    }

    fun onCameraReady() {
        _ui.update { it.copy(cameraMessage = null) }
        if (!counting.get() && _ui.value.pending == null && !_ui.value.busy && flashJob?.isActive != true) publishLiveStatus()
    }

    fun onCameraError(message: String) {
        Log.e(TAG, "camera: $message")
        counting.set(false)
        val blocked = message.contains("did not start", ignoreCase = true) ||
            message.contains("No camera", ignoreCase = true)
        if (blocked) {
            val plain = "Camera is blocked. Allow it in phone settings, then come back."
            _ui.update { it.copy(cameraMessage = plain, status = plain, busy = false, manualCapture = false) }
            return
        }
        _ui.update { it.copy(busy = false, manualCapture = false, cameraMessage = null) }
        showFlash("Could not read the dice. $message", false)
    }

    fun onCaptured(shot: StillCapture) {
        val image = shot.image
        val jpeg = shot.jpeg
        val roi = shot.roi
        Log.i(
            TAG,
            "photo received still ${image.width}x${image.height} raw ${shot.rawW}x${shot.rawH} " +
                "preview view ${shot.previewViewW}x${shot.previewViewH} stream ${shot.previewStream} " +
                "present=${shot.dicePresent} ${shot.framingNote}"
        )
        if (_ui.value.saveAllCaptures) {
            viewModelScope.launch(Dispatchers.IO) {
                saveJpegToDownloads(jpeg, "still-${System.currentTimeMillis()}.jpg")
            }
        }
        val state = _ui.value
        if (!state.running || state.pending != null) {
            Log.w(TAG, "photo dropped running=${state.running} pending=${state.pending != null}")
            _ui.update { it.copy(busy = false) }
            return
        }
        if (!counting.compareAndSet(false, true)) {
            Log.w(TAG, "photo dropped, count already running")
            return
        }
        flashJob?.cancel()
        _ui.update { it.copy(status = "Counting...", statusIsSeven = false, busy = true) }
        viewModelScope.launch {
            var openedCheck = false
            try {
                val det = try {
                    withContext(Dispatchers.Default) { DiceDetector.detectRoll(image, roi) }
                } catch (t: Throwable) {
                    Log.e(TAG, "detect failed", t)
                    null
                }
                val px = medianDiePx(det)
                Log.i(
                    TAG,
                    "detect ok=${det?.ok} conf=${det?.confidence} total=${det?.total} reason=${det?.reason} " +
                        "hint=${det?.hint} diePx=$px crops=${ColoredDiceReader.lastSizeLog} " +
                        "still ${image.width}x${image.height} preview ${shot.previewViewW}x${shot.previewViewH} stream ${shot.previewStream}"
                )
                _ui.update { it.copy(diePx = px) }
                if (!_ui.value.running) {
                    _ui.update { it.copy(busy = false) }
                    publishLiveStatus()
                    return@launch
                }
                val solid = det != null && det.ok && det.confidence == "high" && det.d1 != null && det.d2 != null
                if (solid) {
                    val saved = saveRoll(det!!.d1!!, det.d2!!, jpeg, image, det, userChanged = false, unread = false, reason = null)
                    if (saved <= 0) {
                        showFlash("Could not read the dice. No session is open.", false)
                    } else {
                        feedback.onClearRead(det.total == 7, _ui.value.soundOn)
                    }
                    _ui.update { it.copy(busy = false) }
                } else {
                    val reason = readFailureReason(det, shot, px)
                    feedback.onUnread(_ui.value.soundOn)
                    val rollId = saveRoll(0, 0, jpeg, image, det, userChanged = false, unread = true, reason = reason)
                    openedCheck = true
                    _ui.update {
                        it.copy(
                            status = "Could not read the dice. $reason",
                            statusIsSeven = false,
                            busy = false,
                            pending = PendingCheck(jpeg, det?.d1, det?.d2, det, reason, rollId)
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "count failed", t)
                _ui.update {
                    it.copy(
                        busy = false,
                        status = "Could not read the dice. ${t.message ?: "Something went wrong."}",
                        statusIsSeven = false
                    )
                }
            } finally {
                if (!openedCheck) counting.set(false)
            }
        }
    }

    fun pickPending(die: Int, value: Int) {
        _ui.update { state ->
            val pending = state.pending ?: return@update state
            val d1 = if (die == 0) value else pending.d1
            val d2 = if (die == 1) value else pending.d2
            state.copy(pending = pending.copy(d1 = d1, d2 = d2))
        }
    }

    fun savePending() {
        val pending = _ui.value.pending ?: return
        val d1 = pending.d1 ?: return
        val d2 = pending.d2 ?: return
        viewModelScope.launch {
            val det = pending.detected
            val changed = det == null || !det.ok || det.d1 != d1 || det.d2 != d2
            if (pending.rollId > 0) {
                val roll = db.rolls().get(pending.rollId)
                if (roll != null) {
                    db.rolls().update(
                        roll.copy(
                            d1 = d1,
                            d2 = d2,
                            total = d1 + d2,
                            corrected = changed,
                            unread = false,
                            readReason = null
                        )
                    )
                    showFlash("Logged ${d1 + d2}", d1 + d2 == 7)
                }
            } else {
                saveRoll(d1, d2, pending.jpeg, null, det, userChanged = changed, unread = false, reason = null)
            }
            _ui.update { it.copy(pending = null) }
            counting.set(false)
        }
    }

    fun skipPending() {
        _ui.update { it.copy(pending = null) }
        counting.set(false)
        showFlash("Photo kept. Tap unread to enter the dice.", false)
    }

    fun undoLast() {
        val last = currentRolls.lastOrNull() ?: return
        viewModelScope.launch {
            photos.delete(last.photoPath)
            db.rolls().delete(last)
            showFlash("Last roll removed", false)
        }
    }

    fun setRunning(running: Boolean) {
        flashJob?.cancel()
        _ui.update {
            it.copy(
                running = running,
                status = if (running) "Waiting for dice" else "Paused",
                statusIsSeven = false
            )
        }
    }

    fun completeGuide() {
        prefs.edit().putBoolean(KEY_GUIDE, true).apply()
        _ui.update {
            it.copy(
                guideDone = true,
                running = true,
                status = "Waiting for dice",
                statusIsSeven = false
            )
        }
    }

    fun reopenGuide() {
        _ui.update { it.copy(guideDone = false, showMenu = false, running = false, status = "Paused") }
    }

    fun openMenu() = _ui.update { it.copy(showMenu = true) }
    fun closeMenu() = _ui.update { it.copy(showMenu = false) }
    fun openSettings() = _ui.update { it.copy(showMenu = false, showSettings = true) }
    fun closeSettings() = _ui.update { it.copy(showSettings = false) }
    fun askNewSession() = _ui.update { it.copy(showMenu = false, confirmNew = true) }
    fun cancelNewSession() = _ui.update { it.copy(confirmNew = false) }

    fun confirmNewSession() {
        viewModelScope.launch {
            val session = insertSession()
            sessionId.value = session.id
            _ui.update { it.copy(confirmNew = false, sessionName = session.name, showMenu = false) }
            showFlash("New session started", false)
        }
    }

    fun openSessions() {
        viewModelScope.launch {
            _ui.update { it.copy(showMenu = false, showSessions = true, sessions = loadSessions()) }
        }
    }

    fun closeSessions() = _ui.update { it.copy(showSessions = false) }

    fun openSession(id: Long) {
        viewModelScope.launch {
            val session = db.sessions().get(id) ?: return@launch
            prefs.edit().putLong(KEY_SESSION, id).apply()
            sessionId.value = id
            _ui.update { it.copy(showSessions = false, sessionName = session.name) }
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            val rolls = db.rolls().list(id)
            rolls.forEach { deleteRollFiles(it) }
            val session = db.sessions().get(id) ?: return@launch
            db.sessions().delete(session)
            if (sessionId.value == id) {
                val next = db.sessions().all().firstOrNull() ?: insertSession()
                sessionId.value = next.id
                _ui.update { it.copy(sessionName = next.name) }
            }
            _ui.update { it.copy(sessions = loadSessions()) }
        }
    }

    fun openDetail(id: Long) = _ui.update { it.copy(detailId = id) }
    fun closeDetail() = _ui.update { it.copy(detailId = null, confirmDelete = false) }
    fun askDelete() = _ui.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _ui.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val id = _ui.value.detailId ?: return
        viewModelScope.launch {
            val roll = db.rolls().get(id)
            if (roll != null) {
                deleteRollFiles(roll)
                db.rolls().delete(roll)
            }
            _ui.update { it.copy(detailId = null, confirmDelete = false) }
            showFlash("Roll deleted", false)
        }
    }

    fun correctDetail(die: Int, value: Int) {
        val id = _ui.value.detailId ?: return
        viewModelScope.launch {
            val roll = db.rolls().get(id) ?: return@launch
            val d1 = if (die == 0) value else roll.d1
            val d2 = if (die == 1) value else roll.d2
            val entered = d1 in 1..6 && d2 in 1..6
            val total = if (entered) d1 + d2 else 0
            val detectedTotal = if (roll.detectedD1 != null && roll.detectedD2 != null) {
                roll.detectedD1 + roll.detectedD2
            } else null
            val corrected = roll.corrected || detectedTotal == null || detectedTotal != total
            db.rolls().update(
                roll.copy(
                    d1 = d1,
                    d2 = d2,
                    total = total,
                    corrected = corrected,
                    unread = !entered,
                    readReason = if (entered) null else roll.readReason
                )
            )
        }
    }

    fun detailRoll(): RollEntity? {
        val id = _ui.value.detailId ?: return null
        return currentRolls.find { it.id == id }
    }

    fun setSensitivity(value: Int) {
        val v = value.coerceIn(1, 100)
        prefs.edit().putInt(KEY_SENS, v).apply()
        _ui.update { it.copy(sensitivity = v) }
    }

    fun setSettleMs(ms: Long) {
        val v = ms.coerceIn(300L, 1500L)
        prefs.edit().putLong(KEY_SETTLE, v).apply()
        _ui.update { it.copy(settleMs = v) }
    }

    fun setSound(on: Boolean) {
        prefs.edit().putBoolean(KEY_SOUNDS, on).apply()
        _ui.update { it.copy(soundOn = on) }
    }

    fun setZoom(value: Float) {
        val state = _ui.value
        val hi = state.zoomMax.coerceAtLeast(state.zoomMin)
        val z = value.coerceIn(state.zoomMin, hi)
        prefs.edit().putFloat(KEY_ZOOM, z).apply()
        _ui.update { it.copy(zoom = z) }
    }

    fun nudgeZoom(delta: Float) {
        setZoom(_ui.value.zoom + delta)
    }

    fun onZoomRange(minZoom: Float, maxZoom: Float) {
        val minZ = minZoom.coerceAtLeast(1f)
        val maxZ = maxZoom.coerceAtLeast(minZ)
        _ui.update {
            val z = it.zoom.coerceIn(minZ, maxZ)
            it.copy(zoomMin = minZ, zoomMax = maxZ, zoom = z)
        }
        prefs.edit().putFloat(KEY_ZOOM, _ui.value.zoom).apply()
    }

    /** Zoom toward the box so a small die grows, without sliding the box off screen. */
    fun autoZoom() {
        val state = _ui.value
        val contain = maxZoomContaining(state.frame)
        val cap = min(contain, state.zoomMax).coerceAtLeast(state.zoomMin)
        val die = state.diePx
        val want = if (die != null && die > 0) {
            state.zoom * (70f / die)
        } else {
            val span = max(state.frame.width, state.frame.height).coerceAtLeast(0.05f)
            state.zoom * (0.55f / span)
        }
        setZoom(want.coerceIn(state.zoomMin, cap))
    }

    fun setMarkPhotos(on: Boolean) {
        prefs.edit().putBoolean(KEY_MARK, on).apply()
        _ui.update { it.copy(markPhotos = on) }
    }

    fun setSaveDieCrops(on: Boolean) {
        prefs.edit().putBoolean(KEY_CROPS, on).apply()
        _ui.update { it.copy(saveDieCrops = on) }
    }

    fun setSaveAllCaptures(on: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_ALL, on).apply()
        _ui.update { it.copy(saveAllCaptures = on) }
    }

    fun sharePending() {
        val jpeg = _ui.value.pending?.jpeg ?: return
        shareJpeg(jpeg, "srr-still.jpg")
    }

    fun savePendingToDownloads() {
        val jpeg = _ui.value.pending?.jpeg ?: return
        viewModelScope.launch {
            val name = "still-${System.currentTimeMillis()}.jpg"
            withContext(Dispatchers.IO) { saveJpegToDownloads(jpeg, name) }
            note("Saved the full-resolution still to Downloads/SRR-Tracker")
        }
    }

    fun shareDetailPhoto() {
        val path = detailRoll()?.photoPath ?: return
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.IO) { File(path).readBytes() }
            shareJpeg(jpeg, "srr-still.jpg")
        }
    }

    fun saveDetailToDownloads() {
        val path = detailRoll()?.photoPath ?: return
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.IO) { File(path).readBytes() }
            withContext(Dispatchers.IO) { saveJpegToDownloads(jpeg, "still-${System.currentTimeMillis()}.jpg") }
            note("Saved the full-resolution still to Downloads/SRR-Tracker")
        }
    }

    fun setFrame(rect: NormRect) {
        val next = NormRect(
            left = rect.left.coerceIn(0f, 0.85f),
            top = rect.top.coerceIn(0f, 0.85f),
            right = rect.right.coerceIn(0.15f, 1f),
            bottom = rect.bottom.coerceIn(0.15f, 1f)
        ).let {
            if (it.right - it.left < 0.12f || it.bottom - it.top < 0.12f) _ui.value.frame else it
        }
        prefs.edit()
            .putFloat(KEY_FRAME_L, next.left)
            .putFloat(KEY_FRAME_T, next.top)
            .putFloat(KEY_FRAME_R, next.right)
            .putFloat(KEY_FRAME_B, next.bottom)
            .apply()
        _ui.update { it.copy(frame = next) }
    }

    fun resetFrame() {
        setFrame(CameraBox.asRect())
    }

    fun exportCsv() {
        viewModelScope.launch {
            val sessions = db.sessions().all().sortedBy { it.startedAt }
            val csvSessions = sessions.map { session ->
                val rolls = db.rolls().list(session.id)
                SrrStats.CsvSession(
                    name = session.name,
                    rolls = rolls.filter { !it.unread }.map { r ->
                        SrrStats.CsvRoll(
                            ts = r.ts,
                            d1 = r.d1,
                            d2 = r.d2,
                            total = r.total,
                            corrected = r.corrected,
                            detectedD1 = r.detectedD1,
                            detectedD2 = r.detectedD2,
                            confidence = r.confidence,
                            hasPhoto = r.photoPath != null
                        )
                    }
                )
            }
            val csv = SrrStats.toCSV(csvSessions)
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            val name = "srr-$stamp.csv"
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, name)
            withContext(Dispatchers.IO) {
                file.writeText(csv)
                saveToDownloads(name, csv)
            }
            _effects.emit(UiEffect.ShareFile(file.absolutePath))
            showFlash("Spreadsheet saved", false)
        }
    }

    private fun readFailureReason(det: DiceDetector.Detection?, shot: StillCapture, diePx: Int?): String {
        val missed = if (!shot.dicePresent) {
            "The saved frame does not show a die in the box. "
        } else {
            ""
        }
        val base = when {
            det == null -> "The photo could not be read."
            !det.ok -> det.reason ?: "No pips found."
            det.hint.isNotBlank() -> det.hint
            else -> "Not sure about this read."
        }
        val size = dieSizeSentence(det, diePx)
        return missed + base + size + " Still ${shot.image.width}x${shot.image.height}, preview ${shot.previewViewW}x${shot.previewViewH}. ${shot.framingNote}"
    }

    /**
     * [diePx] is the longest side of the detector's rectangle on the saved still,
     * the median when there are several. It is not the die's size in the preview.
     */
    private fun dieSizeSentence(det: DiceDetector.Detection?, diePx: Int?): String {
        val dice = det?.dice?.filter { it.w > 0 && it.h > 0 }.orEmpty()
        if (dice.isEmpty() || diePx == null || diePx <= 0) return ""
        val boxes = dice.joinToString(", ") { "${it.w}×${it.h}" }
        val small = if (diePx < 60) " Dice look small: zoom in or move the phone closer." else ""
        return " Die box long side is $diePx px on this still (rectangles $boxes). That is the detector's box on the saved photo, not the die's size in the preview.$small"
    }

    private fun medianDiePx(det: DiceDetector.Detection?): Int? {
        val sides = det?.dice?.map { max(it.w, it.h) }?.filter { it > 0 }.orEmpty()
        if (sides.isEmpty()) return null
        return sides.sorted()[sides.size / 2]
    }

    private fun maxZoomContaining(roi: NormRect): Float {
        val dx = max(0.5f - roi.left, roi.right - 0.5f).coerceAtLeast(0.02f)
        val dy = max(0.5f - roi.top, roi.bottom - 0.5f).coerceAtLeast(0.02f)
        return (0.5f / max(dx, dy)).coerceIn(1f, 8f)
    }

    private fun loadFrame(): NormRect {
        if (!prefs.contains(KEY_FRAME_L)) return CameraBox.asRect()
        val loaded = NormRect(
            left = prefs.getFloat(KEY_FRAME_L, CameraBox.LEFT),
            top = prefs.getFloat(KEY_FRAME_T, CameraBox.TOP),
            right = prefs.getFloat(KEY_FRAME_R, CameraBox.RIGHT),
            bottom = prefs.getFloat(KEY_FRAME_B, CameraBox.BOTTOM)
        )
        val oldDefault = abs(loaded.left - FrameTarget.LEFT) < 0.011f &&
            abs(loaded.top - FrameTarget.TOP) < 0.011f &&
            abs(loaded.right - FrameTarget.RIGHT) < 0.011f &&
            abs(loaded.bottom - FrameTarget.BOTTOM) < 0.011f
        if (!oldDefault) return loaded
        val next = CameraBox.asRect()
        prefs.edit()
            .putFloat(KEY_FRAME_L, next.left)
            .putFloat(KEY_FRAME_T, next.top)
            .putFloat(KEY_FRAME_R, next.right)
            .putFloat(KEY_FRAME_B, next.bottom)
            .apply()
        return next
    }

    private suspend fun saveRoll(
        d1: Int,
        d2: Int,
        jpeg: ByteArray,
        image: RgbImage?,
        det: DiceDetector.Detection?,
        userChanged: Boolean,
        unread: Boolean,
        reason: String?
    ): Long {
        val sid = sessionId.value
        if (sid <= 0) return -1
        val ts = System.currentTimeMillis()
        val mark = _ui.value.markPhotos && image != null && det != null
        val keepCrops = _ui.value.saveDieCrops && image != null && det != null && det.dice.isNotEmpty()
        val saved = withContext(Dispatchers.IO) {
            val path = photos.save(jpeg, ts)
            val debug = if (mark) {
                val overlay = DebugMarks.annotate(image!!, det!!)
                photos.save(rgbToJpeg(overlay), ts, "-mark")
            } else null
            val crops = if (keepCrops) {
                det!!.dice.mapIndexed { index, die ->
                    val pad = (max(die.w, die.h) * 0.15f).toInt()
                    val crop = ImageOps.crop(image!!, die.x - pad, die.y - pad, die.w + pad * 2, die.h + pad * 2)
                    photos.save(rgbToJpeg(crop), ts, "-die$index")
                }.joinToString("\n")
            } else null
            Triple(path, debug, crops)
        }
        val total = if (unread) 0 else d1 + d2
        val id = db.rolls().insert(
            RollEntity(
                sessionId = sid,
                ts = ts,
                d1 = d1,
                d2 = d2,
                total = total,
                photoPath = saved.first,
                corrected = userChanged,
                detectedD1 = det?.d1,
                detectedD2 = det?.d2,
                confidence = if (unread) "unread" else det?.confidence,
                pipsJson = det?.pips?.let { encodePips(it) },
                unread = unread,
                readReason = reason,
                debugPath = saved.second,
                cropPaths = saved.third
            )
        )
        if (!unread) {
            val px = medianDiePx(det)
            val size = if (px != null) " · ${px}px" else ""
            Log.i(TAG, "logged $total diePx=$px")
            showFlash("Logged $total$size", total == 7)
        } else {
            Log.i(TAG, "unread saved: $reason")
        }
        return id
    }

    private fun deleteRollFiles(roll: RollEntity) {
        photos.delete(roll.photoPath)
        photos.delete(roll.debugPath)
        roll.cropPaths?.lineSequence()?.forEach { photos.delete(it) }
    }

    private fun showFlash(text: String, seven: Boolean) {
        flashJob?.cancel()
        _ui.update { it.copy(status = text, statusIsSeven = seven) }
        flashJob = viewModelScope.launch {
            delay(2200)
            if (!counting.get() && _ui.value.pending == null) publishLiveStatus()
        }
    }

    private fun publishLiveStatus() {
        val state = _ui.value
        if (!state.guideDone) return
        if (state.cameraMessage != null && !state.running) {
            _ui.update { it.copy(status = state.cameraMessage, statusIsSeven = false) }
            return
        }
        val px = state.diePx
        val size = if (px != null && px > 0) " · ${px}px" else ""
        val text = when {
            !state.running -> "Paused"
            lastInfo?.stage == MotionGate.Stage.DICE_SEEN -> "Dice seen$size"
            lastInfo?.stage == MotionGate.Stage.SETTLING -> "Holding still...$size"
            lastInfo?.stage == MotionGate.Stage.CAPTURING -> "Capturing..."
            lastInfo?.stage == MotionGate.Stage.HOLD -> "Waiting for the next roll$size"
            else -> "Waiting for dice$size"
        }
        _ui.update { it.copy(status = text, statusIsSeven = false) }
    }

    private fun applyRolls(list: List<RollEntity>) {
        currentRolls = list
        val counted = list.filter { !it.unread }
        val totals = counted.map { it.total }
        val running = SrrStats.running(totals)
        val ratioById = counted.mapIndexed { i, roll -> roll.id to running[i].ratio }.toMap()
        val sum = SrrStats.summary(totals)
        val rows = list.indices.reversed().map { i ->
            val roll = list[i]
            RollRow(
                id = roll.id,
                number = i + 1,
                total = roll.total,
                ratio = if (roll.unread) "—" else ratioById[roll.id] ?: "—",
                isSeven = !roll.unread && roll.total == 7,
                unread = roll.unread,
                reason = roll.readReason
            )
        }
        _ui.update {
            it.copy(
                rollCount = sum.rolls,
                sevens = sum.sevens,
                ratio = sum.ratio,
                pctLabel = if (sum.rolls == 0) "no 7s yet" else "${sum.pct} are 7s",
                rows = rows
            )
        }
    }

    private suspend fun insertSession(): Session {
        val now = System.currentTimeMillis()
        val name = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
            .format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
            .let { "Session $it" }
        val id = db.sessions().insert(Session(name = name, startedAt = now))
        prefs.edit().putLong(KEY_SESSION, id).apply()
        return Session(id = id, name = name, startedAt = now)
    }

    private suspend fun loadSessions(): List<SessionSummary> {
        val current = sessionId.value
        return db.sessions().all().map { session ->
            val rolls = db.rolls().list(session.id)
            val sum = SrrStats.summary(rolls.map { it.total })
            SessionSummary(session.id, session.name, sum.rolls, sum.ratio, session.id == current)
        }
    }

    private fun shareJpeg(jpeg: ByteArray, name: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                File(dir, name).apply { writeBytes(jpeg) }
            }
            _effects.emit(UiEffect.ShareImage(file.absolutePath))
        }
    }

    private fun note(text: String) {
        _ui.update { it.copy(notice = text) }
        viewModelScope.launch {
            delay(2500)
            _ui.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    private fun saveJpegToDownloads(jpeg: ByteArray, name: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < 29) {
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "SRR-Tracker") } ?: return
            dir.mkdirs()
            File(dir, name).writeBytes(jpeg)
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SRR-Tracker")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            app.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
        } catch (t: Throwable) {
            Log.w(TAG, "could not save still to Downloads", t)
        }
    }

    private fun saveToDownloads(name: String, csv: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            app.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        } catch (_: Throwable) {
        }
    }

    override fun onCleared() {
        feedback.release()
    }

    companion object {
        private const val KEY_GUIDE = "guideDone"
        private const val KEY_SESSION = "sessionId"
        private const val KEY_SENS = "sensitivity"
        private const val KEY_SETTLE = "settleMs"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SAVE_ALL = "saveAllCaptures"
        private const val KEY_MARK = "markPhotos"
        private const val KEY_CROPS = "saveDieCrops"
        private const val KEY_FRAME_L = "frameL"
        private const val KEY_FRAME_T = "frameT"
        private const val KEY_FRAME_R = "frameR"
        private const val KEY_FRAME_B = "frameB"
        private const val TAG = "SrrTracker"
    }
}
