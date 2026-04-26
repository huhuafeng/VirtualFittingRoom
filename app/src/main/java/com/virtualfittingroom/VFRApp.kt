package com.virtualfittingroom

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class VFRApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val success = OpenCVLoader.initDebug()
        if (success) {
            Log.i("VFRApp", "OpenCV initialized successfully")
        } else {
            Log.e("VFRApp", "OpenCV initialization failed")
        }
    }
}
