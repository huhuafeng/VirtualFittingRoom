package com.virtualfittingroom

import android.app.Application
import android.util.Log

class VFRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initOpenCV()
    }

    private fun initOpenCV() {
        val success = org.opencv.android.OpenCVLoader.initDebug()
        if (success) {
            Log.i("VFRApplication", "OpenCV initialized successfully")
        } else {
            Log.e("VFRApplication", "OpenCV initialization failed")
        }
    }
}
