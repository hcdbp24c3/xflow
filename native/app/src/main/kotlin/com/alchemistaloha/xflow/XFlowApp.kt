package com.alchemistaloha.xflow

import android.app.Application

class XFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
