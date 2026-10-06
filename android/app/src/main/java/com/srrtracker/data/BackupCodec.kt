package com.srrtracker.data

import com.srrtracker.stats.PracticeStats

/**
 * One small JSON file of practice data. No photos. Schema 1.
 * Ids are kept so a session still points at its tag and its rolls.
 */
object BackupCodec {
    const val SCHEMA = 1

    data class Settings(
        val guideDone: Boolean,
        val sensitivity: Int,
        val settleMs: Long,
        val sounds: Boolean,
        val markPhotos: Boolean,
        val saveDieCrops: Boolean,
        val saveAllCaptures: Boolean,
        val zoom: Double,
        val frameL: Double,
        val frameT: Double,
        val frameR: Double,
        val frameB: Double
    )

    data class TagRec(
        val id: Long,
        val name: String,
        val die1Color: Int,
        val die2Color: Int,
        val goal: Int?,
        val sortOrder: Int
    )

    data class SessionRec(
        val id: Long,
        val name: String,
        val startedAt: Long,
        val endedAt: Long?,
        val tagId: Long?
    )

    data class RollRec(
        val id: Long,
        val sessionId: Long,
        val ts: Long,
        val leftFace: Int?,
        val rightFace: Int?,
        val isSeven: Boolean,
        val source: String,
        val total: Int,
        val corrected: Boolean,
        val detectedD1: Int?,
        val detectedD2: Int?,
        val confidence: String?,
        val unread: Boolean,
        val readReason: String?
    )

    data class File(
        val schema: Int,
        val appVersion: String,
        val exportedAt: Long,
        val currentSessionId: Long?,
        val settings: Settings,
        val tags: List<TagRec>,
        val sessions: List<SessionRec>,
        val rolls: List<RollRec>
    )

    class Invalid(message: String) : IllegalArgumentException(message)

    fun export(file: File): String {
        val b = StringBuilder()
        b.append("{\"schema\":").append(file.schema)
        b.append(",\"appVersion\":").append(json(file.appVersion))
        b.append(",\"exportedAt\":").append(file.exportedAt)
        b.append(",\"currentSessionId\":").append(file.currentSessionId?.toString() ?: "null")
        b.append(",\"settings\":").append(settingsJson(file.settings))
        b.append(",\"tags\":[")
        file.tags.forEachIndexed { i, t ->
            if (i > 0) b.append(',')
            b.append("{\"id\":").append(t.id)
            b.append(",\"name\":").append(json(t.name))
            b.append(",\"die1\":").append(json(hex(t.die1Color)))
            b.append(",\"die2\":").append(json(hex(t.die2Color)))
            b.append(",\"goal\":").append(t.goal?.toString() ?: "null")
            b.append(",\"sort\":").append(t.sortOrder).append('}')
        }
        b.append("],\"sessions\":[")
        file.sessions.forEachIndexed { i, s ->
            if (i > 0) b.append(',')
            b.append("{\"id\":").append(s.id)
            b.append(",\"name\":").append(json(s.name))
            b.append(",\"startedAt\":").append(s.startedAt)
            b.append(",\"endedAt\":").append(s.endedAt?.toString() ?: "null")
            b.append(",\"tagId\":").append(s.tagId?.toString() ?: "null").append('}')
        }
        b.append("],\"rolls\":[")
        file.rolls.forEachIndexed { i, r ->
            if (i > 0) b.append(',')
            b.append("{\"id\":").append(r.id)
            b.append(",\"sessionId\":").append(r.sessionId)
            b.append(",\"ts\":").append(r.ts)
            b.append(",\"leftFace\":").append(r.leftFace?.toString() ?: "null")
            b.append(",\"rightFace\":").append(r.rightFace?.toString() ?: "null")
            b.append(",\"isSeven\":").append(if (r.isSeven) "true" else "false")
            b.append(",\"source\":").append(json(r.source))
            b.append(",\"total\":").append(r.total)
            b.append(",\"corrected\":").append(if (r.corrected) "true" else "false")
            b.append(",\"detectedD1\":").append(r.detectedD1?.toString() ?: "null")
            b.append(",\"detectedD2\":").append(r.detectedD2?.toString() ?: "null")
            b.append(",\"confidence\":").append(r.confidence?.let { json(it) } ?: "null")
            b.append(",\"unread\":").append(if (r.unread) "true" else "false")
            b.append(",\"readReason\":").append(r.readReason?.let { json(it) } ?: "null")
            b.append('}')
        }
        b.append("]}")
        return b.toString()
    }

    fun parse(text: String): File {
        val root = try {
            Json.parse(text) as? Map<*, *> ?: throw Invalid("Backup is not a JSON object.")
        } catch (e: Invalid) {
            throw e
        } catch (e: Throwable) {
            throw Invalid("Backup could not be read.")
        }
        val schema = num(root, "schema")?.toInt() ?: throw Invalid("Backup has no schema.")
        if (schema != SCHEMA) throw Invalid("This backup is a newer kind of file.")
        val settings = map(root, "settings") ?: throw Invalid("Backup has no settings.")
        val tags = list(root, "tags").map { tag(it) }
        val sessions = list(root, "sessions").map { session(it) }
        val rolls = list(root, "rolls").map { raw ->
            val r = roll(raw)
            if (r.source == PracticeStats.SOURCE_QUICK) r.copy(leftFace = null, rightFace = null) else r
        }
        val tagIds = tags.map { it.id }.toSet()
        val sessionIds = sessions.map { it.id }.toSet()
        if (tagIds.size != tags.size) throw Invalid("Backup has two tags with the same id.")
        if (sessionIds.size != sessions.size) throw Invalid("Backup has two sessions with the same id.")
        if (rolls.map { it.id }.toSet().size != rolls.size) throw Invalid("Backup has two rolls with the same id.")
        for (s in sessions) {
            if (s.tagId != null && s.tagId !in tagIds) throw Invalid("A session points at a missing tag.")
        }
        for (r in rolls) {
            if (r.sessionId !in sessionIds) throw Invalid("A roll points at a missing session.")
            if (r.source !in setOf(PracticeStats.SOURCE_CAMERA, PracticeStats.SOURCE_MANUAL, PracticeStats.SOURCE_QUICK)) {
                throw Invalid("A roll has an unknown source.")
            }
            if (r.leftFace != null && r.leftFace !in 1..6) throw Invalid("A die face is not 1 to 6.")
            if (r.rightFace != null && r.rightFace !in 1..6) throw Invalid("A die face is not 1 to 6.")
        }
        return File(
            schema = schema,
            appVersion = str(root, "appVersion") ?: "",
            exportedAt = num(root, "exportedAt")?.toLong() ?: 0L,
            currentSessionId = num(root, "currentSessionId")?.toLong(),
            settings = Settings(
                guideDone = bool(settings, "guideDone") ?: false,
                sensitivity = num(settings, "sensitivity")?.toInt() ?: 50,
                settleMs = num(settings, "settleMs")?.toLong() ?: 500L,
                sounds = bool(settings, "sounds") ?: true,
                markPhotos = bool(settings, "markPhotos") ?: true,
                saveDieCrops = bool(settings, "saveDieCrops") ?: true,
                saveAllCaptures = bool(settings, "saveAllCaptures") ?: true,
                zoom = num(settings, "zoom") ?: 1.0,
                frameL = num(settings, "frameL") ?: 0.2,
                frameT = num(settings, "frameT") ?: 0.2,
                frameR = num(settings, "frameR") ?: 0.8,
                frameB = num(settings, "frameB") ?: 0.8
            ),
            tags = tags,
            sessions = sessions,
            rolls = rolls
        )
    }

    /** Same throws and sevens the app would show after a restore. */
    fun signature(file: File): String {
        val bySession = file.rolls.groupBy { it.sessionId }
        val sessions = file.sessions.sortedBy { it.id }.joinToString(";") { s ->
            val entries = bySession[s.id].orEmpty().sortedBy { it.ts }.map {
                PracticeStats.Entry(it.source, it.unread, it.leftFace, it.rightFace, it.isSeven)
            }
            val sum = PracticeStats.summary(entries)
            "${s.id}:${s.tagId}:${sum.rolls}:${sum.sevens}:${sum.ratio}"
        }
        val tags = file.tags.sortedBy { it.id }.joinToString(";") { t ->
            val total = PracticeStats.tagTotal(
                t.id,
                t.goal,
                file.sessions.associate { it.id to it.tagId },
                file.rolls.map {
                    it.sessionId to PracticeStats.Entry(it.source, it.unread, it.leftFace, it.rightFace, it.isSeven)
                }
            )
            "${t.id}:${t.goal}:${total.throws}:${total.sevens}:${total.ratio}"
        }
        return "$sessions|$tags"
    }

    private fun settingsJson(s: Settings): String = buildString {
        append("{\"guideDone\":").append(if (s.guideDone) "true" else "false")
        append(",\"sensitivity\":").append(s.sensitivity)
        append(",\"settleMs\":").append(s.settleMs)
        append(",\"sounds\":").append(if (s.sounds) "true" else "false")
        append(",\"markPhotos\":").append(if (s.markPhotos) "true" else "false")
        append(",\"saveDieCrops\":").append(if (s.saveDieCrops) "true" else "false")
        append(",\"saveAllCaptures\":").append(if (s.saveAllCaptures) "true" else "false")
        append(",\"zoom\":").append(num(s.zoom))
        append(",\"frameL\":").append(num(s.frameL))
        append(",\"frameT\":").append(num(s.frameT))
        append(",\"frameR\":").append(num(s.frameR))
        append(",\"frameB\":").append(num(s.frameB)).append('}')
    }

    private fun tag(raw: Any?): TagRec {
        val m = raw as? Map<*, *> ?: throw Invalid("A tag is not an object.")
        val id = num(m, "id")?.toLong() ?: throw Invalid("A tag has no id.")
        val name = str(m, "name")?.trim().orEmpty()
        if (name.isEmpty()) throw Invalid("A tag has no name.")
        val goal = num(m, "goal")?.toInt()
        if (goal != null && goal < 1) throw Invalid("A goal has to be at least 1.")
        return TagRec(
            id = id,
            name = name,
            die1Color = color(str(m, "die1")),
            die2Color = color(str(m, "die2")),
            goal = goal,
            sortOrder = num(m, "sort")?.toInt() ?: 0
        )
    }

    private fun session(raw: Any?): SessionRec {
        val m = raw as? Map<*, *> ?: throw Invalid("A session is not an object.")
        return SessionRec(
            id = num(m, "id")?.toLong() ?: throw Invalid("A session has no id."),
            name = str(m, "name") ?: "Session",
            startedAt = num(m, "startedAt")?.toLong() ?: 0L,
            endedAt = num(m, "endedAt")?.toLong(),
            tagId = num(m, "tagId")?.toLong()
        )
    }

    private fun roll(raw: Any?): RollRec {
        val m = raw as? Map<*, *> ?: throw Invalid("A roll is not an object.")
        return RollRec(
            id = num(m, "id")?.toLong() ?: throw Invalid("A roll has no id."),
            sessionId = num(m, "sessionId")?.toLong() ?: throw Invalid("A roll has no session."),
            ts = num(m, "ts")?.toLong() ?: 0L,
            leftFace = num(m, "leftFace")?.toInt(),
            rightFace = num(m, "rightFace")?.toInt(),
            isSeven = bool(m, "isSeven") ?: false,
            source = str(m, "source") ?: PracticeStats.SOURCE_CAMERA,
            total = num(m, "total")?.toInt() ?: 0,
            corrected = bool(m, "corrected") ?: false,
            detectedD1 = num(m, "detectedD1")?.toInt(),
            detectedD2 = num(m, "detectedD2")?.toInt(),
            confidence = str(m, "confidence"),
            unread = bool(m, "unread") ?: false,
            readReason = str(m, "readReason")
        )
    }

    fun entity(r: RollRec): RollEntity {
        val faces = r.source != PracticeStats.SOURCE_QUICK
        return RollEntity(
            id = r.id,
            sessionId = r.sessionId,
            ts = r.ts,
            d1 = if (faces) r.leftFace ?: 0 else 0,
            d2 = if (faces) r.rightFace ?: 0 else 0,
            total = r.total,
            photoPath = null,
            corrected = r.corrected,
            detectedD1 = r.detectedD1,
            detectedD2 = r.detectedD2,
            confidence = r.confidence,
            pipsJson = null,
            unread = r.unread,
            readReason = r.readReason,
            debugPath = null,
            cropPaths = null,
            leftFace = if (faces) r.leftFace else null,
            rightFace = if (faces) r.rightFace else null,
            isSeven = r.isSeven,
            source = r.source
        )
    }

    private fun color(hex: String?): Int {
        val h = hex?.removePrefix("#")?.trim().orEmpty()
        val rgb = h.toLongOrNull(16) ?: throw Invalid("A die color is not a color.")
        if (h.length != 6) throw Invalid("A die color is not a color.")
        return (rgb.toInt() and 0xFFFFFF) or 0xFF000000.toInt()
    }

    fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    private fun map(m: Map<*, *>, key: String) = m[key] as? Map<*, *>
    private fun list(m: Map<*, *>, key: String): List<*> {
        val v = m[key] ?: throw Invalid("Backup is missing $key.")
        return v as? List<*> ?: throw Invalid("Backup $key is not a list.")
    }

    private fun str(m: Map<*, *>, key: String): String? = m[key] as? String
    private fun bool(m: Map<*, *>, key: String): Boolean? = m[key] as? Boolean
    private fun num(m: Map<*, *>, key: String): Double? = when (val v = m[key]) {
        is Number -> v.toDouble()
        else -> null
    }

    private fun json(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    private fun num(v: Double): String {
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString() else String.format(java.util.Locale.US, "%.4f", v)
    }

    private object Json {
        fun parse(text: String): Any? = Parser(text).parse()

        private class Parser(val s: String) {
            var i = 0
            fun parse(): Any? {
                skip()
                val v = value()
                skip()
                if (i != s.length) throw Invalid("Backup has extra text.")
                return v
            }

            private fun value(): Any? {
                skip()
                if (i >= s.length) throw Invalid("Backup ended early.")
                return when (s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> num()
                }
            }

            private fun obj(): Map<String, Any?> {
                expect('{')
                val m = LinkedHashMap<String, Any?>()
                skip()
                if (peek('}')) return m
                while (true) {
                    skip()
                    val key = str()
                    skip()
                    expect(':')
                    m[key] = value()
                    skip()
                    if (peek('}')) return m
                    expect(',')
                }
            }

            private fun arr(): List<Any?> {
                expect('[')
                val list = ArrayList<Any?>()
                skip()
                if (peek(']')) return list
                while (true) {
                    list.add(value())
                    skip()
                    if (peek(']')) return list
                    expect(',')
                }
            }

            private fun str(): String {
                expect('"')
                val b = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return b.toString()
                        '\\' -> {
                            if (i >= s.length) throw Invalid("Backup has a broken string.")
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> b.append(e)
                                'b' -> b.append('\b')
                                'f' -> b.append('\u000C')
                                'n' -> b.append('\n')
                                'r' -> b.append('\r')
                                't' -> b.append('\t')
                                'u' -> {
                                    if (i + 4 > s.length) throw Invalid("Backup has a broken string.")
                                    val hex = s.substring(i, i + 4)
                                    i += 4
                                    b.append(hex.toInt(16).toChar())
                                }
                                else -> throw Invalid("Backup has a broken string.")
                            }
                        }
                        else -> b.append(c)
                    }
                }
                throw Invalid("Backup has a broken string.")
            }

            private fun num(): Double {
                val start = i
                peek('-')
                if (i >= s.length || !s[i].isDigit()) throw Invalid("Backup has a bad number.")
                while (i < s.length && s[i].isDigit()) i++
                if (i < s.length && s[i] == '.') {
                    i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(start, i).toDouble()
            }

            private fun lit(word: String, value: Any?): Any? {
                if (!s.startsWith(word, i)) throw Invalid("Backup has unexpected text.")
                i += word.length
                return value
            }

            private fun expect(c: Char) {
                skip()
                if (i >= s.length || s[i] != c) throw Invalid("Backup is missing '$c'.")
                i++
            }

            private fun peek(c: Char): Boolean {
                if (i < s.length && s[i] == c) {
                    i++
                    return true
                }
                return false
            }

            private fun skip() {
                while (i < s.length && s[i].isWhitespace()) i++
            }
        }
    }
}
