package com.lmx.xiaoxuanagent.runtime

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle

object AppForegroundTracker : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    @Volatile
    private var resumedActivityCount: Int = 0

    @Volatile
    private var registered = false

    @Volatile
    private var lastInteractiveAtMs: Long = 0L

    fun register(application: Application) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            application.registerActivityLifecycleCallbacks(this)
            application.registerComponentCallbacks(this)
            registered = true
        }
    }

    fun isAppInForeground(): Boolean = resumedActivityCount > 0

    fun lastInteractiveAtMs(): Long = lastInteractiveAtMs

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        resumedActivityCount += 1
        lastInteractiveAtMs = System.currentTimeMillis()
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
        if (resumedActivityCount > 0) {
            lastInteractiveAtMs = System.currentTimeMillis()
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() = Unit

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            resumedActivityCount = 0
        }
    }
}
