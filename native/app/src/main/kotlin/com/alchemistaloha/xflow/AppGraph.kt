package com.alchemistaloha.xflow

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons: the application context and a scope that lives as long as the process. */
object AppGraph {
    lateinit var appContext: Context
        private set

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
