package com.virtualfittingroom.util

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: ComponentActivity) {

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val requestLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onPermissionResult?.invoke(true)
        } else {
            showPermissionDeniedDialog()
            onPermissionResult?.invoke(false)
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermission(callback: (Boolean) -> Unit) {
        onPermissionResult = callback
        if (hasCameraPermission()) {
            callback(true)
        } else {
            requestLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("需要权限")
            .setMessage("摄像头权限是使用试衣功能的必要条件，请在设置中开启。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
