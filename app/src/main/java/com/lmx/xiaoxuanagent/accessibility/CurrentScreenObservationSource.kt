package com.lmx.xiaoxuanagent.accessibility

import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation

interface CurrentScreenObservationSource {
    suspend fun inspectCurrentScreen(preferredPackageName: String = ""): IndexedScreenObservation?
}
