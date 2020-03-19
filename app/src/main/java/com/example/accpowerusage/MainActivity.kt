package com.example.accpowerusage

import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            when (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION)) {
                PackageManager.PERMISSION_GRANTED -> startActivityTransitionService()
                else -> ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), RC_ACTIVITY_RECOGNITION_PERMISSION)
            }
        else
            startActivityTransitionService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            RC_ACTIVITY_RECOGNITION_PERMISSION -> {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                    startActivityTransitionService()
                else
                    finish()
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startActivityTransitionService() {
        val intent = Intent(applicationContext, MyActivityRecognitionService::class.java)
        stopService(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    companion object {
        const val tag = "AccPowerUsage"
        private const val RC_ACTIVITY_RECOGNITION_PERMISSION = 101
    }
}
