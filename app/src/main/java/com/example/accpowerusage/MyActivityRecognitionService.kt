package com.example.accpowerusage

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.location.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.*

class MyActivityRecognitionService : Service() {
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var transitionPendingIntent: PendingIntent

    override fun onCreate() {
        // region set-up notification
        val notificationId = 98765
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, notificationIntent, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannelId = "com.example.accpowerusage.MyActivityRecognitionService"
            val notificationChannelName = "Accelerometer Power Usage Test"
            val notificationChannel = NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationChannel.lightColor = Color.BLUE
            notificationChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
            val notification = Notification.Builder(applicationContext, notificationChannelId).setContentTitle("Accelerometer Power Usage Test").setContentText("Accelerometer data is being collected now...").setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pendingIntent).build()
            try {
                startForeground(notificationId, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val notification = Notification.Builder(applicationContext).setContentTitle("Accelerometer Power Usage Test").setContentText("Accelerometer data is being collected now...").setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pendingIntent).build()
            try {
                startForeground(notificationId, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // endregion

        // region init variables for (1) activity transition and (2) accelerometer
        ActivityTransitionIntentService.sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        activityRecognitionClient = ActivityRecognition.getClient(applicationContext)
        transitionPendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            Intent(applicationContext, ActivityTransitionIntentService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        // endregion

        // region start (1) activity recognition [ activity transitions ]
        activityRecognitionClient.requestActivityTransitionUpdates(
            ActivityTransitionRequest(
                listOf(
                    ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                    ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
                )
            ),
            transitionPendingIntent
        ).addOnSuccessListener {
            Log.e(MainActivity.tag, "listening for activity transitions...")
        }.addOnFailureListener {
            Log.e(MainActivity.tag, "failed to listen to activity transitions...")
            it.printStackTrace()
        }
        // endregion

        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        // stop (1) activity transition listener and then (2) accelerometer listener
        activityRecognitionClient.removeActivityTransitionUpdates(transitionPendingIntent)
        ActivityTransitionIntentService.stopTimedAccelerometerListener(applicationContext)
        Log.e(MainActivity.tag, "stopped listening for activity transitions...")

        super.onDestroy()
    }
}

class ActivityTransitionIntentService : IntentService("ActivityTransitionIntentService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent != null && ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result!! // development bug
            for (event in result.transitionEvents) {
                logActivityTransitionEvent(event)
                Log.e(MainActivity.tag, "${detectedActivityToStr(event.activityType)} -> ${detectedTransitionToStr(event.transitionType)}")
                vibrate(applicationContext, event.transitionType)
                when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> if (!accelerometerRecording) sensorManager.registerListener(
                        timedAccelerometerListener(applicationContext, 35000L),
                        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                        SensorManager.SENSOR_DELAY_GAME
                    )
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> if (accelerometerRecording) stopTimedAccelerometerListener(applicationContext)
                }
            }
        }
    }

    private fun logActivityTransitionEvent(event: ActivityTransitionEvent) {
        val activityWriter = BufferedWriter(FileWriter(File(filesDir, "activityTransitions.csv"), true))
        val timestamp = System.currentTimeMillis() + (event.elapsedRealTimeNanos - System.nanoTime()) / 1000000L
        activityWriter.write("$timestamp\t${detectedActivityToStr(event.activityType)}\t${detectedTransitionToStr(event.transitionType)}\n")
        activityWriter.close()
    }

    private fun detectedActivityToStr(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.TILTING -> "TILTING"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_FOOT -> "ON_FOOT"
            DetectedActivity.UNKNOWN -> "UNKNOWN"
            else -> "N/A"
        }
    }

    private fun detectedTransitionToStr(transitionType: Int): String {
        return when (transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
            else -> "N/A"
        }
    }

    companion object AccelerometerListener : SensorEventListener {
        private lateinit var accelerometerWriter: BufferedWriter
        lateinit var sensorManager: SensorManager
        var accelerometerRecording = false
        private lateinit var stopperThread: Thread

        override fun onSensorChanged(event: SensorEvent) {
            val timestamp = System.currentTimeMillis() + (event.timestamp - System.nanoTime()) / 1000000L
            accelerometerWriter.write("$timestamp\t${event.accuracy}\t${Arrays.toString(event.values)}\n")
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        fun timedAccelerometerListener(context: Context, durationMs: Long): SensorEventListener {
            if (!accelerometerRecording) {
                stopperThread = Thread {
                    try {
                        Thread.sleep(durationMs)
                    } catch (e: InterruptedException) {
                        Log.e(MainActivity.tag, "stopperThread interrupted")
                    } finally {
                        sensorManager.unregisterListener(this@AccelerometerListener)
                        accelerometerWriter.write('\n'.toInt())
                        accelerometerWriter.close()
                        accelerometerRecording = false
                    }
                    vibrate(context, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                }
                stopperThread.start()
                accelerometerWriter = BufferedWriter(FileWriter(File(context.filesDir, "accelerometer.csv"), true))
                accelerometerRecording = true
                Log.e(MainActivity.tag, "sensing accelerometer...")
            }
            return this
        }

        private fun vibrate(context: Context, transitionType: Int) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val durationMs = when (transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> 500L
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> 1000L
                else -> null
            }
            durationMs!! // development bug
            if (Build.VERSION.SDK_INT >= 26)
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            else
                vibrator.vibrate(durationMs)
        }

        fun stopTimedAccelerometerListener(context: Context) {
            if (accelerometerRecording) {
                stopperThread.interrupt()
                stopperThread.join()
                sensorManager.unregisterListener(this)
                accelerometerWriter.write('\n'.toInt())
                accelerometerWriter.close()
                accelerometerRecording = false
                Log.e(MainActivity.tag, "stopped sensing accelerometer...")
            }
        }
    }
}
