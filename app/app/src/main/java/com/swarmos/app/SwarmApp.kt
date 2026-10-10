package com.swarmos.app

import android.app.Application
import com.swarmos.app.llm.LlamaRuntime

class SwarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    /** At RUNNING_CRITICAL the runtime stops the embedder if nothing uses it; it starts again on the next use. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LlamaRuntime.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LlamaRuntime.onLowMemory()
    }
}
