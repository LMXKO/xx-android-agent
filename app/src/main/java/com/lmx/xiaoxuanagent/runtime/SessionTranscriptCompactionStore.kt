package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionTranscriptCompactionSnapshot(
    val sessionId: String,
    val turn: Int = 0,
    val mode: String = "",
    val trigger: String = "",
    val tokenEstimateBefore: Int = 0,
    val tokenEstimateAfter: Int = 0,
    val compactedTurnCount: Int = 0,
    val preservedTurnCount: Int = 0,
    val boundaryDigest: String = "",
    val lines: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionTranscriptCompactionStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_transcript_compaction.json"
    private const val MAX_SESSIONS = 80
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionTranscriptCompactionSnapshot>()
    private var hydrated = false

    fun readSnapshot(
        sessionId: String,
    ): SessionTranscriptCompactionSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun record(
        snapshot: SessionTranscriptCompactionSnapshot,
    ) {
        synchronized(lock) {
            if (snapshot.sessionId.isBlank()) return
            hydrateIfNeededUnlocked()
            snapshots[snapshot.sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
        }
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("transcript_compaction: ${snapshot.mode.ifBlank { "-" }} | ${snapshot.boundaryDigest.take(96)}")
                addAll(snapshot.lines.take(limit.coerceAtLeast(1)))
            }
        }.orEmpty()

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val snapshot = json.toSnapshot()
                snapshots[snapshot.sessionId] = snapshot
            }
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "snapshots",
                    JSONArray().apply {
                        snapshots.values.sortedByDescending { it.updatedAtMs }.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (snapshots.size > MAX_SESSIONS) {
            val oldest = snapshots.minByOrNull { it.value.updatedAtMs }?.key ?: break
            snapshots.remove(oldest)
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionTranscriptCompactionSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("turn", turn)
            put("mode", mode)
            put("trigger", trigger)
            put("token_estimate_before", tokenEstimateBefore)
            put("token_estimate_after", tokenEstimateAfter)
            put("compacted_turn_count", compactedTurnCount)
            put("preserved_turn_count", preservedTurnCount)
            put("boundary_digest", boundaryDigest)
            put("updated_at_ms", updatedAtMs)
            put("lines", JSONArray(lines))
        }

    private fun JSONObject.toSnapshot(): SessionTranscriptCompactionSnapshot =
        SessionTranscriptCompactionSnapshot(
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            mode = optString("mode"),
            trigger = optString("trigger"),
            tokenEstimateBefore = optInt("token_estimate_before"),
            tokenEstimateAfter = optInt("token_estimate_after"),
            compactedTurnCount = optInt("compacted_turn_count"),
            preservedTurnCount = optInt("preserved_turn_count"),
            boundaryDigest = optString("boundary_digest"),
            lines =
                optJSONArray("lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            add(array.optString(index))
                        }
                    }
                }.orEmpty(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
