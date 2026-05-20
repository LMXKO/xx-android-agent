package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint

internal object SessionRuntimeArtifactHintSupport {
    private const val PLANNER_ARTIFACT_HINT_LIMIT = 4

    fun peekPlannerArtifactHints(
        sessionId: String,
        turn: Int,
    ): List<PlannerArtifactHint> {
        if (sessionId.isBlank()) {
            return emptyList()
        }
        val hints = linkedMapOf<String, PlannerArtifactHint>()
        val planningArtifactId =
            SessionRuntimeArtifactBufferStore.read(sessionId, turn).planningObservationArtifactId.orEmpty()
        addPlannerArtifactHint(hints, ArtifactStore.readArtifactRecord(sessionId, planningArtifactId))
        ArtifactStore.listRecentArtifactRecords(
            sessionId = sessionId,
            beforeTurnInclusive = turn,
            limit = PLANNER_ARTIFACT_HINT_LIMIT * 2,
            types =
                setOf(
                    "planning_observation",
                    "task_result_summary",
                    "execution_verification",
                    "planner_decision",
                    "execution_trace",
                    "turn_failure",
                ),
        ).sortedWith(
            compareByDescending<ArtifactRecord> { plannerArtifactTypePriority(it.type) }
                .thenByDescending { it.createdAt },
        ).forEach { record ->
            addPlannerArtifactHint(hints, record)
        }
        return hints.values.take(PLANNER_ARTIFACT_HINT_LIMIT).toList()
    }

    private fun addPlannerArtifactHint(
        hints: MutableMap<String, PlannerArtifactHint>,
        artifact: ArtifactRecord?,
    ) {
        if (artifact == null || artifact.artifactId.isBlank()) {
            return
        }
        hints.putIfAbsent(
            artifact.artifactId,
            PlannerArtifactHint(
                artifactId = artifact.artifactId,
                type = artifact.type,
                summary = artifact.summary,
            ),
        )
    }

    private fun plannerArtifactTypePriority(type: String): Int =
        when (type) {
            "planning_observation" -> 5
            "planner_decision" -> 4
            "execution_verification" -> 3
            "task_result_summary" -> 2
            "execution_trace" -> 1
            else -> 0
        }
}

