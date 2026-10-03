package com.srrtracker.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srrtracker.data.AppDatabase
import com.srrtracker.data.PhotoStore
import com.srrtracker.data.RollEntity
import com.srrtracker.data.Session
import com.srrtracker.detect.DiceDetector
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
    val isSeven: Boolean
)

data class PendingCheck(
    val jpeg: ByteArray,
    val d1: Int?,
    val d2: Int?,
    val detected: DiceDetector.Detection?,
    val reason: String
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
    val soundOn: Boolean = false,
    val sessionName: String = "",
    val cameraMessage: String? = null,
    val busy: Boolean = false,
    val manualCapture: Boolean = false
)

sealed interface UiEffect {
    data class ShareFile(val path: String) : UiEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val photos = PhotoStore(app)
    private val prefs = app.getSharedPreferences("srr", Application.MODE_PRIVATE)
    private val feedback = RollFeedback()

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
        val sound = prefs.getBoolean(KEY_SOUND, false)
        _ui.update {
            it.copy(
                guideDone = guide,
                sensitivity = sensitivity,
                settleMs = settle,
                soundOn = sound,
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

    fun onCaptured(image: RgbImage, jpeg: ByteArray, roi: NormRect?) {
        Log.i(TAG, "photo received ${image.width}x${image.height}")
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
                Log.i(
                    TAG,
                    "detect ok=${det?.ok} conf=${det?.confidence} total=${det?.total} reason=${det?.reason} hint=${det?.hint}"
                )
                if (!_ui.value.running) {
                    _ui.update { it.copy(busy = false) }
                    publishLiveStatus()
                    return@launch
                }
                val solid = det != null && det.ok && det.confidence == "high" && det.d1 != null && det.d2 != null
                if (solid) {
                    val saved = saveRoll(det!!.d1!!, det.d2!!, jpeg, det, userChanged = false)
                    if (!saved) {
                        showFlash("Could not read the dice. No session is open.", false)
                    }
                    _ui.update { it.copy(busy = false) }
                } else {
                    val reason = readFailureReason(det)
                    openedCheck = true
                    _ui.update {
                        it.copy(
                            status = "Could not read the dice. $reason",
                            statusIsSeven = false,
                            busy = false,
                            pending = PendingCheck(jpeg, det?.d1, det?.d2, det, reason)
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
            saveRoll(d1, d2, pending.jpeg, det, userChanged = changed)
            _ui.update { it.copy(pending = null) }
            counting.set(false)
        }
    }

    fun skipPending() {
        _ui.update { it.copy(pending = null) }
        counting.set(false)
        publishLiveStatus()
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
            rolls.forEach { photos.delete(it.photoPath) }
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
                photos.delete(roll.photoPath)
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
            val total = d1 + d2
            val detectedTotal = if (roll.detectedD1 != null && roll.detectedD2 != null) {
                roll.detectedD1 + roll.detectedD2
            } else null
            val corrected = roll.corrected || detectedTotal == null || detectedTotal != total
            db.rolls().update(roll.copy(d1 = d1, d2 = d2, total = total, corrected = corrected))
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
        prefs.edit().putBoolean(KEY_SOUND, on).apply()
        _ui.update { it.copy(soundOn = on) }
    }

    fun exportCsv() {
        viewModelScope.launch {
            val sessions = db.sessions().all().sortedBy { it.startedAt }
            val csvSessions = sessions.map { session ->
                val rolls = db.rolls().list(session.id)
                SrrStats.CsvSession(
                    name = session.name,
                    rolls = rolls.map { r ->
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

    private fun readFailureReason(det: DiceDetector.Detection?): String {
        if (det == null) return "The photo could not be read."
        if (!det.ok) return det.reason ?: "No pips found."
        if (det.hint.isNotBlank()) return det.hint
        return "Not sure about this read."
    }

    private suspend fun saveRoll(
        d1: Int,
        d2: Int,
        jpeg: ByteArray,
        det: DiceDetector.Detection?,
        userChanged: Boolean
    ): Boolean {
        val sid = sessionId.value
        if (sid <= 0) return false
        val ts = System.currentTimeMillis()
        val path = withContext(Dispatchers.IO) { photos.save(jpeg, ts) }
        val total = d1 + d2
        db.rolls().insert(
            RollEntity(
                sessionId = sid,
                ts = ts,
                d1 = d1,
                d2 = d2,
                total = total,
                photoPath = path,
                corrected = userChanged,
                detectedD1 = det?.d1,
                detectedD2 = det?.d2,
                confidence = det?.confidence,
                pipsJson = det?.pips?.let { encodePips(it) }
            )
        )
        val state = _ui.value
        feedback.onLogged(total == 7, state.soundOn)
        Log.i(TAG, "logged $total")
        showFlash("Logged $total", total == 7)
        return true
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
        val text = when {
            !state.running -> "Paused"
            lastInfo?.stage == MotionGate.Stage.DICE_SEEN -> "Dice seen"
            lastInfo?.stage == MotionGate.Stage.SETTLING -> "Holding still..."
            lastInfo?.stage == MotionGate.Stage.CAPTURING -> "Capturing..."
            lastInfo?.stage == MotionGate.Stage.HOLD -> "Waiting for the next roll"
            else -> "Waiting for dice"
        }
        _ui.update { it.copy(status = text, statusIsSeven = false) }
    }

    private fun applyRolls(list: List<RollEntity>) {
        currentRolls = list
        val totals = list.map { it.total }
        val running = SrrStats.running(totals)
        val sum = SrrStats.summary(totals)
        val rows = list.indices.reversed().map { i ->
            RollRow(
                id = list[i].id,
                number = i + 1,
                total = list[i].total,
                ratio = running[i].ratio,
                isSeven = list[i].total == 7
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
        private const val KEY_SOUND = "sound"
        private const val TAG = "SrrTracker"
    }
}
