package com.lmx.xiaoxuanagent.runtime

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

object SessionRuntimeStore {
    private const val MAX_LOADED_SESSIONS = 48

    private val lock = Any()
    private val states = LinkedHashMap<String, SessionRuntimeState>()
    private var activeSessionId = ""
    private var fallbackState = SessionRuntimeState()
    private val currentSessionContext = ThreadLocal<String?>()

    fun read(): SessionRuntimeState =
        synchronized(lock) {
            stateForResolvedSessionUnlocked() ?: fallbackState
        }

    fun readSession(
        sessionId: String,
    ): SessionRuntimeState =
        synchronized(lock) {
            states[sessionId] ?: SessionRuntimeState()
        }

    fun readLoadedStates(
        limit: Int = MAX_LOADED_SESSIONS,
    ): List<SessionRuntimeState> =
        synchronized(lock) {
            val ordered =
                buildList {
                    activeSessionId.takeIf { it.isNotBlank() }?.let { activeId ->
                        states[activeId]?.let(::add)
                    }
                    states.entries
                        .sortedByDescending { it.value.updatedAtMs }
                        .forEach { (sessionId, state) ->
                            if (sessionId != activeSessionId) {
                                add(state)
                            }
                        }
                }
            ordered.take(limit.coerceAtLeast(1))
        }

    fun hasSession(
        sessionId: String,
    ): Boolean =
        synchronized(lock) {
            sessionId.isNotBlank() && states.containsKey(sessionId)
        }

    fun activeSessionId(): String =
        synchronized(lock) {
            activeSessionId
        }

    fun currentContextSessionId(): String = currentSessionContext.get().orEmpty()

    fun session(): RuntimeSession = read().session

    fun safety(): RuntimeSafetyState = read().safety

    fun activateSession(
        sessionId: String,
    ): Boolean =
        synchronized(lock) {
            if (sessionId.isBlank() || sessionId !in states) {
                false
            } else {
                activeSessionId = sessionId
                true
            }
        }

    fun importSessionState(
        sessionId: String,
        state: SessionRuntimeState,
        makeActive: Boolean = false,
    ): SessionRuntimeState =
        synchronized(lock) {
            val normalizedSessionId = state.session.sessionId.ifBlank { sessionId }
            storeStateUnlocked(
                sessionId = normalizedSessionId,
                state =
                    if (normalizedSessionId.isBlank()) {
                        state
                    } else {
                        state.copy(session = state.session.copy(sessionId = normalizedSessionId))
                    },
                makeActive = makeActive,
            )
            stateForResolvedSessionUnlocked(normalizedSessionId) ?: fallbackState
        }

    fun clearSession(
        sessionId: String,
    ) {
        synchronized(lock) {
            if (sessionId.isBlank()) return
            states.remove(sessionId)
            if (activeSessionId == sessionId) {
                activeSessionId =
                    states.entries.maxByOrNull { it.value.updatedAtMs }?.key.orEmpty()
            }
        }
    }

    fun sessionContext(
        sessionId: String,
    ): CoroutineContext = currentSessionContext.asContextElement(sessionId.ifBlank { activeSessionId() })

    fun dispatch(
        command: SessionCommand,
    ): SessionRuntimeState =
        synchronized(lock) {
            val currentSessionId = resolveSessionIdUnlocked()
            val current = stateForResolvedSessionUnlocked(currentSessionId) ?: fallbackState
            val nowMs = System.currentTimeMillis()
            val nextState = SessionRuntimeReducer.reduce(current, command, nowMs)
            val nextSessionId = nextState.session.sessionId.ifBlank { currentSessionId }
            storeStateUnlocked(
                sessionId = nextSessionId,
                state = nextState,
                makeActive = currentSessionContext.get().isNullOrBlank(),
            )
            RuntimeEventStore.append(
                command = command,
                beforeState = current,
                afterState = nextState,
                timestamp = nowMs,
            )
            nextState
        }

    fun <T> mutate(
        reducer: (SessionRuntimeState) -> Pair<SessionRuntimeState, T>,
    ): T =
        synchronized(lock) {
            val currentSessionId = resolveSessionIdUnlocked()
            val current = stateForResolvedSessionUnlocked(currentSessionId) ?: fallbackState
            val (nextState, result) = reducer(current)
            val nextSessionId = nextState.session.sessionId.ifBlank { currentSessionId }
            storeStateUnlocked(
                sessionId = nextSessionId,
                state = nextState,
                makeActive = currentSessionContext.get().isNullOrBlank(),
            )
            result
        }

    private fun resolveSessionIdUnlocked(): String =
        currentSessionContext.get().orEmpty().ifBlank { activeSessionId }

    private fun stateForResolvedSessionUnlocked(
        sessionId: String = resolveSessionIdUnlocked(),
    ): SessionRuntimeState? =
        if (sessionId.isBlank()) {
            null
        } else {
            states[sessionId]
        }

    private fun storeStateUnlocked(
        sessionId: String,
        state: SessionRuntimeState,
        makeActive: Boolean,
    ) {
        if (sessionId.isBlank()) {
            fallbackState = state
            if (makeActive) {
                activeSessionId = ""
            }
            return
        }
        states[sessionId] = state.copy(session = state.session.copy(sessionId = sessionId))
        if (makeActive) {
            activeSessionId = sessionId
        }
        trimUnlocked()
    }

    private fun trimUnlocked() {
        if (states.size <= MAX_LOADED_SESSIONS) return
        val removable =
            states.entries
                .filterNot { it.key == activeSessionId }
                .sortedBy { it.value.updatedAtMs }
                .take((states.size - MAX_LOADED_SESSIONS).coerceAtLeast(0))
                .map { it.key }
        removable.forEach(states::remove)
    }
}
