package com.virtualfittingroom.util

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: AppCompatActivity) {

    fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        if (hasCameraPermission()) {
            onResult(true)
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA
        )
        pendingCallback = onResult
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingCallback?.invoke(true)
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                    // User denied with "Don't ask again"
                    AlertDialog.Builder(activity)
                        .setTitle("需要摄像头权限")
                        .setMessage("请在设置中开启摄像头权限以使用虚拟试衣功能")
                        .setPositiveButton("去设置") { _, _ ->
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = android.net.Uri.fromParts("package", activity.packageName, null)
                            activity.startActivity(intent)
                        }
                        .setNegativeButton("取消") { dialog, _ ->
                            dialog.dismiss()
                            pendingCallback?.invoke(false)
                        }
                        .show()
                } else {
                    Toast.makeText(activity, "需要摄像头权限才能使用", Toast.LENGTH_SHORT).show()
                    pendingCallback?.invoke(false)
                }
            }
            pendingCallback = null
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
        private var pendingCallback: ((Boolean) -> Unit)? = null
    }
}
