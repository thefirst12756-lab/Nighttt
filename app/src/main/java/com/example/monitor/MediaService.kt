package com.example.monitor

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.webrtc.*

class MediaService : Service(), SignalingClient.Listener {

    companion object {
        const val CHANNEL_ID = "media_service_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.monitor.ACTION_STOP"
        const val EXTRA_ROOM = "room"
        const val EXTRA_SERVER = "server"
        const val DEFAULT_SERVER_URL = "https://signaling-kdjp-production.up.railway.app"
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): MediaService = this@MediaService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private var wakeLock: PowerManager.WakeLock? = null
    private var signaling: SignalingClient? = null

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var previewSink: VideoSink? = null
        set(value) {
            field = value
            value?.let { localVideoTrack?.addSink(it) }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }

        val room = intent?.getStringExtra(EXTRA_ROOM) ?: "room1"
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: DEFAULT_SERVER_URL

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        startStreaming(server, room)
        return START_STICKY
    }

    private fun startStreaming(serverUrl: String, room: String) {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        setupLocalTracks()

        signaling = SignalingClient(serverUrl, room, this).also { it.connect() }
    }

    private fun setupLocalTracks() {
        val enumerator = Camera2Enumerator(applicationContext)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("لا توجد كاميرا متاحة")

        videoCapturer = enumerator.createCapturer(cameraName, null)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource)
        previewSink?.let { localVideoTrack?.addSink(it) }

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signaling?.sendIceCandidate(candidate)
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            }
        )

        val streamId = "localStream"
        peerConnection?.addTrack(localVideoTrack, listOf(streamId))
        peerConnection?.addTrack(localAudioTrack, listOf(streamId))
    }

    override fun onJoinResult(ok: Boolean, error: String?) {}

    override fun onPeerReady() {
        createPeerConnection()
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                signaling?.sendOffer(desc)
            }
        }, constraints)
    }

    override fun onRemoteAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    override fun onRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    override fun onPeerLeft() {
        peerConnection?.close()
        peerConnection = null
    }

    private fun stopStreaming() {
        signaling?.disconnect()
        signaling = null

        peerConnection?.close()
        peerConnection = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()

        if (::peerConnectionFactory.isInitialized) peerConnectionFactory.dispose()
        if (::eglBase.isInitialized) eglBase.release()
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("البث شغّال الآن")
            .setContentText("افتح التطبيق لإيقاف البث")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "خدمة البث", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monitor:streamingWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        stopStreaming()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
