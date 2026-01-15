package com.amua.audiodownloader

import android.app.Application
import android.util.Log

/**
 * Application class for Amua Audio Downloader.
 */
class AudioDownloaderApp : Application() {

    companion object {
        private const val TAG = "AudioDownloaderApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Amua Audio Downloader initialized")
    }
}
