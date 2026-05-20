package com.lmx.xiaoxuanagent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuntimeStoreTest {
    @Test
    fun `session context isolates state mutations per session`() =
        runBlocking {
            val sessionA = "runtime_store_a"
            val sessionB = "runtime_store_b"
            SessionRuntimeStore.clearSession(sessionA)
            SessionRuntimeStore.clearSession(sessionB)
            try {
                SessionRuntimeStore.importSessionState(
                    sessionId = sessionA,
                    state = baseState(sessionA, "task-a", updatedAtMs = 10L),
                    makeActive = true,
                )
                SessionRuntimeStore.importSessionState(
                    sessionId = sessionB,
                    state = baseState(sessionB, "task-b", updatedAtMs = 20L),
                    makeActive = false,
                )

                runBlocking(SessionRuntimeStore.sessionContext(sessionA)) {
                    SessionRuntimeStore.mutate { current ->
                        current.copy(
                            session = current.session.copy(task = "task-a-updated"),
                            updatedAtMs = 101L,
                        ) to Unit
                    }
                }
                runBlocking(SessionRuntimeStore.sessionContext(sessionB)) {
                    SessionRuntimeStore.mutate { current ->
                        current.copy(
                            session = current.session.copy(task = "task-b-updated"),
                            updatedAtMs = 202L,
                        ) to Unit
                    }
                }

                assertEquals("task-a-updated", SessionRuntimeStore.readSession(sessionA).session.task)
                assertEquals("task-b-updated", SessionRuntimeStore.readSession(sessionB).session.task)
                assertEquals(sessionA, SessionRuntimeStore.activeSessionId())
                assertEquals(
                    listOf(sessionA, sessionB),
                    SessionRuntimeStore.readLoadedStates(limit = 4).map { it.session.sessionId },
                )
            } finally {
                SessionRuntimeStore.clearSession(sessionA)
                SessionRuntimeStore.clearSession(sessionB)
            }
        }

    @Test
    fun `platform snapshot prefers loaded non active session state`() {
        val activeSession = "snapshot_active"
        val backgroundSession = "snapshot_background"
        SessionRuntimeStore.clearSession(activeSession)
        SessionRuntimeStore.clearSession(backgroundSession)
        try {
            SessionRuntimeStore.importSessionState(
                sessionId = activeSession,
                state = baseState(activeSession, "active-task", updatedAtMs = 10L),
                makeActive = true,
            )
            SessionRuntimeStore.importSessionState(
                sessionId = backgroundSession,
                state = baseState(backgroundSession, "background-task", updatedAtMs = 30L),
                makeActive = false,
            )

            val snapshot =
                SessionPlatformSnapshotFacadeSupport.readSessionSnapshot(
                    sessionId = backgroundSession,
                    eventLimit = 4,
                )

            assertEquals(backgroundSession, snapshot.sessionId)
            assertEquals(backgroundSession, snapshot.state.session.sessionId)
            assertEquals("background-task", snapshot.state.session.task)
            assertTrue(snapshot.state.updatedAtMs >= 30L)
        } finally {
            SessionRuntimeStore.clearSession(activeSession)
            SessionRuntimeStore.clearSession(backgroundSession)
        }
    }

    private fun baseState(
        sessionId: String,
        task: String,
        updatedAtMs: Long,
    ): SessionRuntimeState =
        SessionRuntimeState(
            session =
                RuntimeSession(
                    sessionId = sessionId,
                    task = task,
                    targetPackageName = "com.test.$sessionId",
                ),
            updatedAtMs = updatedAtMs,
        )
}
