package com.lmx.xiaoxuanagent.runtime

data class RuntimeSnapshot(
    val foregroundPackage: String,
    val pageState: AppPageState,
    val eventType: String,
    val observationSignature: String,
    val visibleElementCount: Int,
    val hint: String,
)
