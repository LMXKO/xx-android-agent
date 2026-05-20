package com.lmx.xiaoxuanagent

import android.app.Application
import com.lmx.xiaoxuanagent.runtime.AgentHookBootstrap
import com.lmx.xiaoxuanagent.runtime.AppForegroundTracker
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext

class XiaoxuanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRuntimeContext.init(this)
        AppForegroundTracker.register(this)
        AgentHookBootstrap.registerDefaults()
    }
}
