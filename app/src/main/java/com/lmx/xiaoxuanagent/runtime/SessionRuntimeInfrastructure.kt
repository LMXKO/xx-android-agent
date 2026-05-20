package com.lmx.xiaoxuanagent.runtime

internal object SessionRuntimeBridgeSupport {
    private val lock = Any()
    private var bridge: SessionBridge = NullSessionBridge

    fun install(
        sessionBridge: SessionBridge,
    ) {
        synchronized(lock) {
            bridge = sessionBridge
        }
    }

    fun publish(
        event: SessionBridgeEvent,
    ) {
        val current =
            synchronized(lock) {
                bridge
            }
        current.publish(event)
    }

    fun publishArtifactRecorded(
        sessionId: String,
        turn: Int,
        artifact: ArtifactRecord,
    ) {
        publish(
            SessionBridgeEvent.ArtifactRecorded(
                sessionId = sessionId,
                turn = turn,
                artifactId = artifact.artifactId,
                type = artifact.type,
                summary = artifact.summary,
            ),
        )
    }
}

internal object SessionRuntimeArtifactBufferStore {
    private val lock = Any()
    private val turnArtifacts = mutableMapOf<String, TurnArtifactBuffer>()

    fun update(
        sessionId: String,
        turn: Int,
        updater: (TurnArtifactBuffer) -> TurnArtifactBuffer,
    ) {
        val key = turnKey(sessionId, turn)
        synchronized(lock) {
            val current = turnArtifacts[key] ?: TurnArtifactBuffer()
            turnArtifacts[key] = updater(current)
        }
    }

    fun consume(
        sessionId: String,
        turn: Int,
    ): TurnArtifactRefs {
        val key = turnKey(sessionId, turn)
        synchronized(lock) {
            val refs = turnArtifacts.remove(key) ?: TurnArtifactBuffer()
            return refs.toRefs()
        }
    }

    fun clear(
        sessionId: String,
        turn: Int,
    ) {
        synchronized(lock) {
            turnArtifacts.remove(turnKey(sessionId, turn))
        }
    }

    fun read(
        sessionId: String,
        turn: Int,
    ): TurnArtifactBuffer =
        synchronized(lock) {
            turnArtifacts[turnKey(sessionId, turn)] ?: TurnArtifactBuffer()
        }

    fun turnKey(
        sessionId: String,
        turn: Int,
    ): String = "$sessionId#$turn"
}

internal object SessionRuntimePreflightSupport {
    fun publishBlocked(
        sessionId: String,
        task: String,
        entrySource: String,
        status: String,
        error: String,
        hint: String,
        reason: String,
    ) {
        SessionRuntimeStore.dispatch(
            SessionCommand.BlockPreflight(
                session =
                    SessionRuntimeTransitionFactory.blockedSession(
                        sessionId = sessionId,
                        task = task,
                        entrySource = entrySource,
                        statusModel = AgentUiStatus.resolve(status),
                    ),
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastAction = "",
                        lastResult = "",
                        lastError = error,
                        hint = hint,
                    ),
                reason = reason,
            ),
        )
        SessionRuntimeStateSupport.publishRuntimeState(sessionId)
    }
}
