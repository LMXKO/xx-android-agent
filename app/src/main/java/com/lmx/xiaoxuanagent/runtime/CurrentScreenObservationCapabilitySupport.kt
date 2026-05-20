package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.accessibility.AgentAccessibilityService
import com.lmx.xiaoxuanagent.accessibility.CurrentScreenObservationSource

internal object CurrentScreenObservationCapabilitySupport {
    @Volatile
    private var sourceProvider: () -> CurrentScreenObservationSource? = { AgentAccessibilityService.current() }

    fun setSourceProviderForTest(provider: () -> CurrentScreenObservationSource?) {
        sourceProvider = provider
    }

    fun resetForTest() {
        sourceProvider = { AgentAccessibilityService.current() }
    }

    suspend fun inspectCurrentScreen(
        request: SessionCapabilityRequest,
    ): SessionCapabilityResult {
        val source = sourceProvider.invoke()
            ?: return SessionCapabilityResult(
                success = false,
                summary = "当前无障碍服务未连接，无法读取实时屏幕。",
                sessionId = request.sessionId.ifBlank { SessionRuntimeStore.read().session.sessionId },
            )
        val preferredPackageName = request.payload["preferred_package"].orEmpty()
        val elementLimit = request.payload["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10
        val observation =
            source.inspectCurrentScreen(preferredPackageName = preferredPackageName)
                ?: return SessionCapabilityResult(
                    success = false,
                    summary = "当前屏幕观察失败。",
                    sessionId = request.sessionId.ifBlank { SessionRuntimeStore.read().session.sessionId },
                )
        val sessionId = request.sessionId.ifBlank { SessionRuntimeStore.read().session.sessionId }
        val screen = observation.observation
        val lines =
            CurrentScreenObservationFormatter.format(
                observation = observation,
                elementLimit = elementLimit,
            ) +
                CurrentScreenObservationFormatter.recommendedCommands(sessionId = sessionId)
                    .map { "command | $it" }
        return SessionCapabilityResult(
            success = true,
            summary =
                buildString {
                    append("screen=").append(screen.packageName.ifBlank { "-" })
                    append(" page=").append(screen.pageState.ifBlank { "-" })
                    append(" elements=").append(screen.elements.size)
                },
            sessionId = sessionId,
            payloadLines = lines,
        )
    }
}
