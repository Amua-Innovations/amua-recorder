package com.amua.audiodownloader

import android.app.Application
import android.util.Log

/**
 * Application class for AmuaRecorder.
 */
class AudioDownloaderApp : Application() {

    companion object {
        private const val TAG = "AmuaRecorderApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AmuaRecorder initialized")
    }
}
