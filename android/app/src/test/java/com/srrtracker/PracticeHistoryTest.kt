package com.srrtracker

import com.srrtracker.data.BackupCodec
import com.srrtracker.data.HistoryMigration
import com.srrtracker.stats.PracticeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SRR across camera, manual, and quick rolls; tag totals when a goal changes;
 * the upgrade of old rolls; and a backup that survives export, a wipe, and import.
 */
class PracticeHistoryTest {
    @Test
    fun mixedSourcesUseOnlyCountedThrowsAndTheSevenFlag() {
        val cameraTen = PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, false, 6, 4, false)
        val cameraSeven = PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, false, 3, 4, true)
        val unread = PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, true, null, null, false)
        val unresolved = PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, false, null, null, false)
        val manualSeven = PracticeStats.Entry(PracticeStats.SOURCE_MANUAL, false, 1, 6, true)
        val quickSeven = PracticeStats.quick(true)
        val quickMiss = PracticeStats.quick(false)

        assertNull(quickSeven.leftFace)
        assertNull(quickSeven.rightFace)
        assertFalse(PracticeStats.counts(unread))
        assertFalse(PracticeStats.counts(unresolved))
        assertTrue(PracticeStats.counts(quickMiss))
        assertTrue(PracticeStats.counts(cameraTen))

        val entries = listOf(cameraTen, unread, cameraSeven, unresolved, manualSeven, quickSeven, quickMiss)
        val sum = PracticeStats.summary(entries)
        assertEquals(5, sum.rolls)
        assertEquals(3, sum.sevens)
        assertEquals("1:1.7", sum.ratio)

        val running = PracticeStats.running(entries)
        assertEquals(5, running.size)
        assertEquals("—", running[0].ratio)
        assertEquals("1:2.0", running[1].ratio)
        assertEquals("1:1.5", running[2].ratio)
        assertEquals(3, running.last().sevens)
    }

    @Test
    fun tagTotalsFollowRollsAndANewGoalKeepsTheSameProgress() {
        val hardWay = 10L
        val other = 11L
        val sessionTag = mapOf(1L to hardWay, 2L to null, 3L to hardWay, 4L to other)
        val rolls = listOf(
            1L to PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, false, 5, 2, true),
            1L to PracticeStats.Entry(PracticeStats.SOURCE_CAMERA, true, null, null, false),
            1L to PracticeStats.quick(false),
            2L to PracticeStats.quick(true),
            3L to PracticeStats.Entry(PracticeStats.SOURCE_MANUAL, false, 6, 6, false),
            4L to PracticeStats.quick(true)
        )
        val atFiveThousand = PracticeStats.tagTotal(hardWay, 5000, sessionTag, rolls)
        val atThreeThousand = PracticeStats.tagTotal(hardWay, 3200, sessionTag, rolls)

        assertEquals(3, atFiveThousand.throws)
        assertEquals(1, atFiveThousand.sevens)
        assertEquals("1:3.0", atFiveThousand.ratio)
        assertEquals(5000, atFiveThousand.goal)
        assertEquals(atFiveThousand.throws, atThreeThousand.throws)
        assertEquals(atFiveThousand.sevens, atThreeThousand.sevens)
        assertEquals(atFiveThousand.ratio, atThreeThousand.ratio)
        assertEquals(3200, atThreeThousand.goal)

        val otherTotal = PracticeStats.tagTotal(other, null, sessionTag, rolls)
        assertEquals(1, otherTotal.throws)
        assertEquals(1, otherTotal.sevens)
        assertNull(otherTotal.goal)
    }

    @Test
    fun migrationKeepsCountedFacesAndDropsUnreadOnes() {
        val seven = HistoryMigration.migrateRoll(HistoryMigration.LegacyRoll(6, 1, unread = false))
        assertEquals(6, seven.leftFace)
        assertEquals(1, seven.rightFace)
        assertTrue(seven.isSeven)
        assertEquals(PracticeStats.SOURCE_CAMERA, seven.source)

        val twelve = HistoryMigration.migrateRoll(HistoryMigration.LegacyRoll(6, 6, unread = false))
        assertEquals(6, twelve.leftFace)
        assertEquals(6, twelve.rightFace)
        assertFalse(twelve.isSeven)

        val unread = HistoryMigration.migrateRoll(HistoryMigration.LegacyRoll(2, 1, unread = true))
        assertNull(unread.leftFace)
        assertNull(unread.rightFace)
        assertFalse(unread.isSeven)
        assertEquals(PracticeStats.SOURCE_CAMERA, unread.source)

        val blank = HistoryMigration.migrateRoll(HistoryMigration.LegacyRoll(0, 0, unread = false))
        assertNull(blank.leftFace)
        assertFalse(blank.isSeven)

        assertEquals("Hard Way", HistoryMigration.HARD_WAY)
        assertEquals(0xFFFF3B3B.toInt(), HistoryMigration.HARD_WAY_RED)
    }

    @Test
    fun backupRoundTripKeepsIdsRelationshipsAndStats() {
        val original = sampleBackup()
        val json = BackupCodec.export(original)
        assertFalse(json.contains("photoPath"))
        assertFalse(json.contains("debugPath"))
        assertFalse(json.contains("cropPaths"))
        assertFalse(json.contains("pipsJson"))
        assertFalse(json.contains("jpeg"))

        val restored = BackupCodec.parse(json)
        assertEquals(BackupCodec.signature(original), BackupCodec.signature(restored))
        assertEquals(original.sessions.map { it.id }, restored.sessions.map { it.id })
        assertEquals(original.tags.map { it.id to it.goal }, restored.tags.map { it.id to it.goal })
        assertEquals(
            original.tags.map { it.die1Color to it.die2Color },
            restored.tags.map { it.die1Color to it.die2Color }
        )
        assertEquals(original.sessions.map { it.tagId }, restored.sessions.map { it.tagId })
        val quick = restored.rolls.filter { it.source == PracticeStats.SOURCE_QUICK }
        assertEquals(2, quick.size)
        assertTrue(quick.all { it.leftFace == null && it.rightFace == null })
        assertEquals(listOf(4L, 7L, 11L, 12L, 13L), restored.rolls.map { it.id })
        assertEquals(1.25, restored.settings.zoom, 0.0001)
        assertEquals(0.15, restored.settings.frameT, 0.0001)

        val wiped = original.copy(tags = emptyList(), sessions = emptyList(), rolls = emptyList(), currentSessionId = null)
        assertFalse(BackupCodec.signature(wiped) == BackupCodec.signature(original))
        val imported = BackupCodec.parse(json)
        assertEquals(BackupCodec.signature(original), BackupCodec.signature(imported))

        val movedGoal = imported.copy(
            tags = imported.tags.map { if (it.id == 3L) it.copy(goal = 100) else it }
        )
        val before = PracticeStats.tagTotal(
            3L,
            5000,
            original.sessions.associate { it.id to it.tagId },
            original.rolls.map { it.sessionId to PracticeStats.Entry(it.source, it.unread, it.leftFace, it.rightFace, it.isSeven) }
        )
        val after = PracticeStats.tagTotal(
            3L,
            100,
            movedGoal.sessions.associate { it.id to it.tagId },
            movedGoal.rolls.map { it.sessionId to PracticeStats.Entry(it.source, it.unread, it.leftFace, it.rightFace, it.isSeven) }
        )
        assertEquals(before.throws, after.throws)
        assertEquals(before.sevens, after.sevens)
        assertEquals(100, after.goal)

        val entity = BackupCodec.entity(imported.rolls.first { it.source == PracticeStats.SOURCE_QUICK })
        assertNull(entity.leftFace)
        assertNull(entity.rightFace)
        assertEquals(0, entity.d1)
        assertEquals(0, entity.d2)
        assertNull(entity.photoPath)
    }

    @Test
    fun backupRejectsABrokenFileAndStripsFacesFromQuickEntries() {
        val bad = BackupCodec.export(sampleBackup()).replace("\"schema\":1", "\"schema\":9")
        try {
            BackupCodec.parse(bad)
            throw AssertionError("a newer schema should be refused")
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("newer"))
        }

        val withFaces = BackupCodec.export(sampleBackup())
            .replace("\"source\":\"quick\",\"total\":7", "\"source\":\"quick\",\"total\":7")
        val forced = withFaces.replace(
            "\"leftFace\":null,\"rightFace\":null,\"isSeven\":true,\"source\":\"quick\"",
            "\"leftFace\":9,\"rightFace\":1,\"isSeven\":true,\"source\":\"quick\""
        )
        val parsed = BackupCodec.parse(forced)
        val quickSeven = parsed.rolls.first { it.isSeven && it.source == PracticeStats.SOURCE_QUICK }
        assertNull(quickSeven.leftFace)
        assertNull(quickSeven.rightFace)
    }

    private fun sampleBackup(): BackupCodec.File {
        val settings = BackupCodec.Settings(
            guideDone = true,
            sensitivity = 50,
            settleMs = 500,
            sounds = true,
            markPhotos = true,
            saveDieCrops = false,
            saveAllCaptures = true,
            zoom = 1.25,
            frameL = 0.2,
            frameT = 0.15,
            frameR = 0.8,
            frameB = 0.9
        )
        return BackupCodec.File(
            schema = BackupCodec.SCHEMA,
            appVersion = "1.0.1",
            exportedAt = 1_700_000_000_000,
            currentSessionId = 8L,
            settings = settings,
            tags = listOf(
                BackupCodec.TagRec(3L, "Hard Way", HistoryMigration.HARD_WAY_RED, HistoryMigration.HARD_WAY_RED, 5000, 0),
                BackupCodec.TagRec(5L, "Toss", 0xFF3B82F6.toInt(), 0xFFF4F4F5.toInt(), null, 1)
            ),
            sessions = listOf(
                BackupCodec.SessionRec(8L, "Morning", 1_700_000_000_000, 1_700_000_180_000, 3L),
                BackupCodec.SessionRec(9L, "Untagged", 1_700_000_200_000, 1_700_000_200_000, null)
            ),
            rolls = listOf(
                BackupCodec.RollRec(4L, 8L, 1_700_000_010_000, 6, 4, false, PracticeStats.SOURCE_CAMERA, 10, false, 6, 4, "high", false, null),
                BackupCodec.RollRec(7L, 8L, 1_700_000_020_000, null, null, true, PracticeStats.SOURCE_QUICK, 7, false, null, null, null, false, null),
                BackupCodec.RollRec(11L, 8L, 1_700_000_030_000, null, null, false, PracticeStats.SOURCE_CAMERA, 0, false, null, null, "unread", true, "pips unclear"),
                BackupCodec.RollRec(12L, 9L, 1_700_000_210_000, null, null, false, PracticeStats.SOURCE_QUICK, 0, false, null, null, null, false, null),
                BackupCodec.RollRec(13L, 9L, 1_700_000_220_000, 1, 6, true, PracticeStats.SOURCE_MANUAL, 7, true, null, null, null, false, null)
            )
        )
    }
}
