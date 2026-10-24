package com.example.monitor

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var roomCodeField: EditText

    private var mediaService: MediaService? = null
    private var bound = false
    private val eglBase: EglBase = EglBase.create()

    private val permissionsNeeded = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionRequestCode = 1001

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MediaService.LocalBinder
            mediaService = localBinder.getService()
            mediaService?.previewSink = localRenderer
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            mediaService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRenderer = findViewById(R.id.localRenderer)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        roomCodeField = findViewById(R.id.roomCode)

        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)

        requestNeededPermissions()

        btnStart.setOnClickListener { startStreaming() }
        btnStop.setOnClickListener { stopStreaming() }
    }

    private fun requestNeededPermissions() {
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, "لازم تسمح بالكاميرا والميكروفون عشان البث يشتغل", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startStreaming() {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasCam || !hasMic) {
            requestNeededPermissions()
            return
        }

        val room = roomCodeField.text.toString().trim().ifEmpty { "room1" }

        val intent = Intent(this, MediaService::class.java).apply {
            putExtra(MediaService.EXTRA_ROOM, room)
            putExtra(MediaService.EXTRA_SERVER, MediaService.DEFAULT_SERVER_URL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(Intent(this, MediaService::class.java), connection, Context.BIND_AUTO_CREATE)

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        roomCodeField.isEnabled = false
    }

    private fun stopStreaming() {
        if (bound) {
            mediaService?.previewSink = null
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, MediaService::class.java))

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        roomCodeField.isEnabled = true
    }

    override fun onDestroy() {
        if (bound) {
            mediaService?.previewSink = null
            unbindService(connection)
            bound = false
        }
        localRenderer.release()
        eglBase.release()
        super.onDestroy()
    }
}
