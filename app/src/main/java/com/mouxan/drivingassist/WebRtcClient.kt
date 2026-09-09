package com.mouxan.drivingassist

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebRtcClient：连接车机 webrtcd（http://<ip>:5001/stream），接收 H.264 视频流并硬解渲染。
 *
 * 流程：
 *  1. 创建 PeerConnectionFactory（启用 MediaCodec 硬解）；
 *  2. 创建 PeerConnection，添加本地 offer；
 *  3. HTTP POST 发送 SDP offer 到 webrtcd /stream；
 *  4. 接收 SDP answer 并 setRemoteDescription；
 *  5. ICE 交换完成后接收远程视频轨，渲染到 SurfaceViewRenderer。
 */
class WebRtcClient(
  private val context: Context,
  private val deviceIp: String,
  private val camera: String = "road"
) {

  companion object {
    private const val TAG = "WebRtcClient"
    private const val WEBRTCD_PORT = 5001
    private const val SDP_TIMEOUT_SEC = 30L
  }

  private val http = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(SDP_TIMEOUT_SEC, TimeUnit.SECONDS)
    .build()

  private var peerConnectionFactory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null
  private var eglBase: EglBase? = null
  private var surfaceViewRenderer: SurfaceViewRenderer? = null
  private var videoTrack: VideoTrack? = null

  private val sdpLatch = CountDownLatch(1)
  private var localSdp: SessionDescription? = null

  fun init(renderer: SurfaceViewRenderer) {
    surfaceViewRenderer = renderer
    eglBase = EglBase.create()
    renderer.init(eglBase?.eglBaseContext, null)
    renderer.setMirror(false)
    renderer.setEnableHardwareScaler(true)

    // 初始化 PeerConnectionFactory，启用 MediaCodec 硬解
    val options = PeerConnectionFactory.InitializationOptions.builder(context)
      .setEnableInternalTracer(false)
      .createInitializationOptions()
    PeerConnectionFactory.initialize(options)

    val encoderFactory = org.webrtc.DefaultVideoEncoderFactory(
      eglBase?.eglBaseContext,
      true,
      true
    )
    val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

    peerConnectionFactory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(encoderFactory)
      .setVideoDecoderFactory(decoderFactory)
      .createPeerConnectionFactory()

    createPeerConnection()
  }

  private fun createPeerConnection() {
    val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
      sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
      override fun onIceCandidate(candidate: IceCandidate?) {
        // webrtcd 使用 trickle ICE，但 openpilot 的 webrtcd 目前不支持客户端发送 candidate
        // 所以这里只记录，不发送
        Log.d(TAG, "onIceCandidate: ${candidate?.sdpMid}")
      }

      override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved: ${candidates?.size}")
      }

      override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
        val receiver = transceiver?.receiver
        val track = receiver?.track()
        if (track is VideoTrack) {
          Log.i(TAG, "onTrack: video track received, id=${track.id()}")
          videoTrack = track
          track.addSink(surfaceViewRenderer)
        }
      }

      override fun onAddStream(stream: MediaStream?) {
        Log.d(TAG, "onAddStream: ${stream?.id}")
      }

      override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
        Log.d(TAG, "onAddTrack: ${receiver?.id()}")
      }

      override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        Log.i(TAG, "onIceConnectionChange: $state")
        if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
          UiPrefs.appendLog(context, "webrtc ice $state")
        }
      }

      override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
      }

      override fun onSignalingChange(state: PeerConnection.SignalingState?) {
        Log.d(TAG, "onSignalingChange: $state")
      }

      override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "onIceGatheringChange: $state")
      }

      override fun onRemoveStream(stream: MediaStream?) {
        Log.d(TAG, "onRemoveStream: ${stream?.id}")
      }

      override fun onDataChannel(channel: org.webrtc.DataChannel?) {
        Log.d(TAG, "onDataChannel: ${channel?.label()}")
      }

      override fun onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded")
      }
    }) ?: throw IllegalStateException("Failed to create PeerConnection")
  }

  fun connect() {
    val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")

    // 创建 offer
    val constraints = MediaConstraints().apply {
      mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
      mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
    }

    pc.createOffer(object : SdpObserver {
      override fun onCreateSuccess(desc: SessionDescription?) {
        Log.i(TAG, "createOffer success, type=${desc?.type}")
        localSdp = desc
        pc.setLocalDescription(object : SdpObserver {
          override fun onCreateSuccess(p0: SessionDescription?) {}
          override fun onSetSuccess() {
            Log.i(TAG, "setLocalDescription success")
            sdpLatch.countDown()
          }
          override fun onCreateFailure(p0: String?) {}
          override fun onSetFailure(p0: String?) {
            Log.e(TAG, "setLocalDescription failed: $p0")
            sdpLatch.countDown()
          }
        }, desc)
      }

      override fun onSetSuccess() {}
      override fun onCreateFailure(error: String?) {
        Log.e(TAG, "createOffer failed: $error")
        sdpLatch.countDown()
      }
      override fun onSetFailure(p0: String?) {}
    }, constraints)

    // 等待本地 SDP 就绪
    Thread {
      try {
        sdpLatch.await(5, TimeUnit.SECONDS)
        val sdp = localSdp ?: throw IllegalStateException("No local SDP")
        sendOfferToServer(sdp)
      } catch (e: Exception) {
        Log.e(TAG, "connect failed", e)
        UiPrefs.appendLog(context, "webrtc connect failed: ${e.message}")
      }
    }.start()
  }

  private fun sendOfferToServer(offer: SessionDescription) {
    val url = "http://$deviceIp:$WEBRTCD_PORT/stream"
    val json = JSONObject().apply {
      put("sdp", offer.description)
      put("cameras", listOf(camera))
      put("enabled", true)
      put("bridge_services_in", emptyList<String>())
      put("bridge_services_out", emptyList<String>())
    }

    val body = json.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
      .url(url)
      .post(body)
      .build()

    try {
      http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
        }
        val respJson = JSONObject(resp.body!!.string())
        if (!respJson.optBoolean("ok", false)) {
          throw IllegalStateException("webrtc offer failed: ${respJson.optString("error")}")
        }
        val answerSdp = respJson.getString("sdp")
        val answerType = respJson.optString("type", "answer")
        Log.i(TAG, "Received SDP answer, type=$answerType")

        val answer = SessionDescription(
          SessionDescription.Type.fromCanonicalForm(answerType),
          answerSdp
        )
        peerConnection?.setRemoteDescription(object : SdpObserver {
          override fun onCreateSuccess(p0: SessionDescription?) {}
          override fun onSetSuccess() {
            Log.i(TAG, "setRemoteDescription success, WebRTC connected")
            UiPrefs.appendLog(context, "webrtc connected to $deviceIp")
          }
          override fun onCreateFailure(p0: String?) {}
          override fun onSetFailure(p0: String?) {
            Log.e(TAG, "setRemoteDescription failed: $p0")
            UiPrefs.appendLog(context, "webrtc setRemoteDescription failed: $p0")
          }
        }, answer)
      }
    } catch (e: Exception) {
      Log.e(TAG, "sendOfferToServer failed", e)
      UiPrefs.appendLog(context, "webrtc offer failed: ${e.message}")
      throw e
    }
  }

  fun release() {
    try { videoTrack?.removeSink(surfaceViewRenderer) } catch (_: Exception) {}
    try { peerConnection?.close() } catch (_: Exception) {}
    try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
    try { surfaceViewRenderer?.release() } catch (_: Exception) {}
    try { eglBase?.release() } catch (_: Exception) {}
    videoTrack = null
    peerConnection = null
    peerConnectionFactory = null
    surfaceViewRenderer = null
    eglBase = null
  }
}
