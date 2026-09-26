package com.jambus.heji
 
import org.junit.Assert.assertEquals
import org.junit.Test
 
class MoveHistoryTargetPolicyTest {
 
    private fun createChange(
        id: String,
        sourceToTarget: Map<String, String>,
        committedAt: Long
    ) = MoveBundleChange(
        id = id,
        vaultId = "vault1",
        sourceToTarget = sourceToTarget,
        bundleSourceToTarget = emptyMap(),
        beforeSha256 = sourceToTarget.keys.associateWith { "sha-before" },
        afterSha256 = sourceToTarget.values.associateWith { "sha-after" },
        state = LocalChangeState.COMMITTED,
        committedAt = committedAt,
        acknowledgedProviders = emptySet()
    )
 
    @Test
    fun testUriGraph() {
        val url = "https://graph.microsoft.com/v1.0/me/drive/items/12345:/Engineering%20Center%20%E7%BB%84%E7%BB%87%E8%B0%83%E6%95%B4%20MOC.md:/content"
        assertEquals(url, OneDriveUrlPolicy.graph(url))
    }

    @Test
    fun resolvesSingleMoveTarget() {
        val change = createChange("c1", mapOf("Daily Notes/note.md" to "test/note.md"), 1000L)
        val changes = listOf(change)
 
        val finalTarget = MoveHistoryTargetPolicy.resolveFinalTarget("test/note.md", 0, changes)
        assertEquals("test/note.md", finalTarget)
    }
 
    @Test
    fun resolvesChainedMovesTarget() {
        val c1 = createChange("c1", mapOf("A/note.md" to "B/note.md"), 1000L)
        val c2 = createChange("c2", mapOf("B/note.md" to "C/note.md"), 2000L)
        val changes = listOf(c1, c2)
 
        assertEquals("C/note.md", MoveHistoryTargetPolicy.resolveFinalTarget("B/note.md", 0, changes))
        assertEquals("C/note.md", MoveHistoryTargetPolicy.resolveFinalTarget("C/note.md", 1, changes))
    }
 
    @Test
    fun resolvesMoveBackTargetWithoutCycle() {
        val c1 = createChange("c1", mapOf("A/note.md" to "B/note.md"), 1000L)
        val c2 = createChange("c2", mapOf("B/note.md" to "A/note.md"), 2000L)
        val changes = listOf(c1, c2)
 
        assertEquals("A/note.md", MoveHistoryTargetPolicy.resolveFinalTarget("B/note.md", 0, changes))
        assertEquals("A/note.md", MoveHistoryTargetPolicy.resolveFinalTarget("A/note.md", 1, changes))
    }
 
    @Test
    fun resolvesBackAndForthMultipleMovesMatchingDeviceScenario() {
        val c1 = createChange("c1", mapOf("Daily Notes/2026-09-01.md" to "test/2026-09-01.md"), 1789691301395L)
        val c2 = createChange("c2", mapOf("test/2026-09-01.md" to "Daily Notes/2026-09-01.md"), 1789834073153L)
        val c3 = createChange("c3", mapOf("Daily Notes/2026-09-01.md" to "test/2026-09-01.md"), 1789835159512L)
        val changes = listOf(c1, c2, c3)
 
        // For change 1, its target was test, then moved to Daily Notes, then back to test
        assertEquals("test/2026-09-01.md", MoveHistoryTargetPolicy.resolveFinalTarget("test/2026-09-01.md", 0, changes))
        // For change 2, its target was Daily Notes, then moved to test
        assertEquals("test/2026-09-01.md", MoveHistoryTargetPolicy.resolveFinalTarget("Daily Notes/2026-09-01.md", 1, changes))
        // For change 3, its target was test
        assertEquals("test/2026-09-01.md", MoveHistoryTargetPolicy.resolveFinalTarget("test/2026-09-01.md", 2, changes))
    }
 
    @Test
    fun preservesIndependentPathsAcrossMultipleChanges() {
        val c1 = createChange("c1", mapOf("A/note1.md" to "B/note1.md"), 1000L)
        val c2 = createChange("c2", mapOf("X/note2.md" to "Y/note2.md"), 2000L)
        val c3 = createChange("c3", mapOf("B/note1.md" to "C/note1.md"), 3000L)
        val changes = listOf(c1, c2, c3)
 
        assertEquals("C/note1.md", MoveHistoryTargetPolicy.resolveFinalTarget("B/note1.md", 0, changes))
        assertEquals("Y/note2.md", MoveHistoryTargetPolicy.resolveFinalTarget("Y/note2.md", 1, changes))
        assertEquals("C/note1.md", MoveHistoryTargetPolicy.resolveFinalTarget("C/note1.md", 2, changes))
    }

    @Test
    fun targetsToVerifyIncludesLocallyPresentTarget() {
        val change = createChange("c1", mapOf("A/note.md" to "B/note.md"), 1000L)
        val targets = MoveTargetVerificationPolicy.targetsToVerify(
            change.sourceToTarget,
            0,
            listOf(change),
            localFiles = mapOf("B/note.md" to "content"),
            remoteFiles = emptyMap<String, String>()
        )
        assertEquals(setOf("B/note.md"), targets)
    }

    @Test
    fun targetsToVerifyIncludesTargetWhenSourceIsOnRemoteAndNeedsTrash() {
        val change = createChange("c1", mapOf("A/note.md" to "B/note.md"), 1000L)
        val targets = MoveTargetVerificationPolicy.targetsToVerify(
            change.sourceToTarget,
            0,
            listOf(change),
            localFiles = emptyMap<String, String>(),
            remoteFiles = mapOf("A/note.md" to "remoteItem")
        )
        assertEquals(setOf("B/note.md"), targets)
    }

    @Test
    fun targetsToVerifyOmitsTargetWhenSourceAbsentOnRemoteAndTargetAbsentLocally() {
        val change = createChange("c1", mapOf("A/image.jpg" to "B/image.jpg"), 1000L)
        val targets = MoveTargetVerificationPolicy.targetsToVerify(
            change.sourceToTarget,
            0,
            listOf(change),
            localFiles = emptyMap<String, String>(),
            remoteFiles = emptyMap<String, String>()
        )
        assertEquals(emptySet<String>(), targets)
    }

    @Test
    fun targetsToVerifyHandlesMixedDeletedAssetsAndExistingNote() {
        val change = createChange("c1", mapOf(
            "Daily Notes/2026-09-01.md" to "test/2026-09-01.md",
            "Daily Notes/assets/2026-09-01/191147-xp6n-c.jpg" to "test/assets/2026-09-01/191147-xp6n-c.jpg"
        ), 1000L)
        val targets = MoveTargetVerificationPolicy.targetsToVerify(
            change.sourceToTarget,
            0,
            listOf(change),
            localFiles = mapOf("test/2026-09-01.md" to "file"),
            remoteFiles = emptyMap<String, String>()
        )
        // Note target is verified because it exists locally; absent asset is omitted because neither source nor target exists
        assertEquals(setOf("test/2026-09-01.md"), targets)
    }
}

