package com.lmx.xiaoxuanagent.runtime

import android.content.Context

object AppRuntimeContext {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
