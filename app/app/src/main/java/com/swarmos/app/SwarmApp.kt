package com.swarmos.app

import android.app.Application

class SwarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
