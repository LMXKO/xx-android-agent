package com.lmx.xiaoxuanagent.runtime

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactStoreRetentionSupportTest {
    @Test
    fun `read lifecycle snapshot exposes turn coverage and deletion risk`() {
        val root = Files.createTempDirectory("artifact_lifecycle_test").toFile()
        try {
            val sessionDir = File(root, "session_1").apply { mkdirs() }
            val now = System.currentTimeMillis()
            File(sessionDir, "index.jsonl").writeText(
                buildString {
                    append(indexLine("a1", "planning_observation", 1, now - 10L * 24L * 60L * 60L * 1000L, "obs old"))
                    append('\n')
                    append(indexLine("a2", "planning_observation", 2, now - 9L * 24L * 60L * 60L * 1000L, "obs older"))
                    append('\n')
                    append(indexLine("a3", "planner_decision", 2, now - 2L * 60L * 60L * 1000L, "decision recent"))
                    append('\n')
                    append(indexLine("a4", "task_result_summary", 3, now - 30L * 60L * 1000L, "result pinned"))
                    append('\n')
                },
            )

            val snapshot =
                ArtifactStoreRetentionSupport.readLifecycleSnapshot(
                    sessionId = "session_1",
                    policy =
                        ArtifactRetentionPolicy(
                            maxAgeMs = 3L * 24L * 60L * 60L * 1000L,
                            maxArtifactsPerSession = 3,
                            maxArtifactsPerType = 1,
                            maxArtifactsPerFamily = 2,
                            keepLatestPerFamily = 1,
                            hotArtifactWindowMs = 60L * 60L * 1000L,
                        ),
                    beforeTurnInclusive = Int.MAX_VALUE,
                    resolveSessionDir = { sessionId -> File(root, sessionId) },
                )

            assertEquals(4, snapshot.totalArtifacts)
            assertEquals("turns=1..3 count=3", snapshot.turnWindowSummary)
            assertEquals(3, snapshot.distinctTurnCount)
            assertTrue(snapshot.deletionCandidateCount >= 2)
            assertTrue(snapshot.deletionRiskSummary.contains("session_cap="))
            assertTrue(snapshot.recentArtifactLines.first().contains("turn=3"))
            assertTrue(snapshot.lines.any { it.startsWith("window=") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun indexLine(
        artifactId: String,
        type: String,
        turn: Int,
        createdAt: Long,
        summary: String,
    ): String =
        JSONObject().apply {
            put("artifact_id", artifactId)
            put("type", type)
            put("summary", summary)
            put("turn", turn)
            put("created_at", createdAt)
            put("file_name", "$artifactId.json")
            put("payload_hash", "hash_$artifactId")
        }.toString()
}
