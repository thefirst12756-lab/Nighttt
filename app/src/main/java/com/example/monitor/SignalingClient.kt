package com.example.monitor

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingClient(
    private val serverUrl: String,
    private val room: String,
    private val listener: Listener
) {
    interface Listener {
        fun onPeerReady()
        fun onRemoteAnswer(sdp: SessionDescription)
        fun onRemoteIceCandidate(candidate: IceCandidate)
        fun onPeerLeft()
        fun onJoinResult(ok: Boolean, error: String?)
    }

    private var socket: Socket? = null
    private val TAG = "SignalingClient"

    fun connect() {
        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
        }
        socket = IO.socket(serverUrl, opts)

        socket?.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "connected, joining room=$room as broadcaster")
            val payload = JSONObject().put("room", room).put("role", "broadcaster")
            socket?.emit("join", payload, Ack { args ->
                val res = args.getOrNull(0) as? JSONObject
                val ok = res?.optBoolean("ok", false) ?: false
                listener.onJoinResult(ok, if (ok) null else res?.optString("error"))
            })
        }

        socket?.on("peer-ready") { listener.onPeerReady() }

        socket?.on("video-answer") { args ->
            val d = args.getOrNull(0) as? JSONObject ?: return@on
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, d.optString("sdp"))
            listener.onRemoteAnswer(sdp)
        }

        socket?.on("new-ice-candidate") { args ->
            val d = args.getOrNull(0) as? JSONObject ?: return@on
            val candidate = IceCandidate(
                d.optString("sdpMid"),
                d.optInt("sdpMLineIndex"),
                d.optString("candidate")
            )
            listener.onRemoteIceCandidate(candidate)
        }

        socket?.on("peer-left") { listener.onPeerLeft() }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "connect error: ${args.joinToString()}")
        }

        socket?.connect()
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().put("type", "offer").put("sdp", sdp.description)
        socket?.emit("video-offer", payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
        socket?.emit("new-ice-candidate", payload)
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    private fun Array<Any>.getOrNull(i: Int): Any? = if (i < size) this[i] else null
}

private fun Socket.on(event: String, handler: (Array<Any>) -> Unit): Socket =
    this.on(event, Emitter.Listener { args -> handler(args) })

private fun Socket.emit(event: String, data: Any, ack: Ack): Socket =
    this.emit(event, data, ack)

private fun Ack(onAck: (Array<Any>) -> Unit): io.socket.client.Ack =
    io.socket.client.Ack { args -> onAck(args) }
