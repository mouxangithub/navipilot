package com.mouxan.drivingassist

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/**
 * 一键全屏投屏页（应用默认页）：
 *  - 启动即自动发现设备（UDP 7705 广播，多台设备弹选择）并自动连接；
 *  - libVLC 播放设备 screencastd（MPEG-TS over TCP 7080），network-caching 可调；
 *  - 触摸手势按比例换算为设备 2160x1080 坐标后经 TCP 7071 回传 vtouchd（带本地触点回显）；
 *  - 底部浮动车控条：◀超车 / −5 / +5 / 超车▶（carrot UDP 指令）；
 *  - 断流/失联自动回到搜寻状态循环重试；搜寻超时给出参数引导 + 重试；
 *  - 右上角「操作界面」进入 CP搭子操作主页；连接事件写入本地埋点日志。
 */
class ScreenMirrorActivity : Activity(), IVLCVout.OnNewVideoLayoutListener {

  companion object {
    private const val SCREEN_W = 2160
    private const val SCREEN_H = 1080
    private const val TOUCH_PORT = 7071
    private const val VIDEO_PORT = 7080
    private const val SEARCH_GUIDE_AFTER_ROUNDS = 5 // 5 轮(约 30s)无设备 → 引导
  }

  private val handler = Handler(Looper.getMainLooper())
  private var libVlc: LibVLC? = null
  private var player: MediaPlayer? = null
  private var touchSocket: Socket? = null
  private var touchThread: Thread? = null
  private var connectThread: Thread? = null
  private val touchQueue = LinkedBlockingQueue<String>(256)
  private lateinit var hint: TextView
  private lateinit var retryButton: TextView
  private lateinit var touchIndicator: View
  private var deviceIp: String? = null
  private var devicePort = 43001
  private var searchRounds = 0

  /** 视频流异常信号：连接线程收到后回到搜寻状态 */
  @Volatile
  private var streamError = false

  @Volatile
  private var resumed = false

  /** 多设备选择的用户决定（发现线程经 latch 等待 UI 选择） */
  @Volatile
  private var chosenIp: String? = null
  private var devicePickLatch: CountDownLatch? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_screen_mirror)
    hideSystemBars()

    hint = findViewById(R.id.mirror_hint)
    retryButton = findViewById(R.id.btn_retry)
    touchIndicator = findViewById(R.id.touch_indicator)
    deviceIp = intent.getStringExtra("device_ip")
    findViewById<TextView>(R.id.btn_open_main).setOnClickListener {
      startActivity(Intent(this, MainActivity::class.java))
    }
    retryButton.setOnClickListener {
      retryButton.visibility = View.GONE
      searchRounds = 0
      connectThread?.interrupt()
      startAutoConnect()
    }

    try {
      libVlc = LibVLC(this.applicationContext)
      player = MediaPlayer(libVlc).apply {
        setEventListener { ev ->
          if (ev.type == org.videolan.libvlc.MediaPlayer.Event.EndReached
            || ev.type == org.videolan.libvlc.MediaPlayer.Event.EncounteredError
          ) {
            streamError = true
          }
        }
        val vout = getVLCVout()
        vout.setVideoView(findViewById<SurfaceView>(R.id.mirror_surface))
        vout.attachViews(this@ScreenMirrorActivity)
      }
    } catch (e: Exception) {
      UiPrefs.appendLog(this, "vlc init error: ${e.message}")
      Toast.makeText(this, "视频引擎初始化失败：${e.message}", Toast.LENGTH_LONG).show()
    }

    val surface = findViewById<SurfaceView>(R.id.mirror_surface)
    surface.setOnTouchListener { v, ev ->
      val ip = deviceIp ?: return@setOnTouchListener true
      val x = (ev.x / Math.max(1, v.width) * SCREEN_W).toInt()
      val y = (ev.y / Math.max(1, v.height) * SCREEN_H).toInt()
      when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
          showTouchIndicator(ev.x, ev.y)
          sendTouch(if (ev.actionMasked == MotionEvent.ACTION_DOWN) "D $x $y" else "M $x $y")
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          touchIndicator.visibility = View.GONE
          sendTouch("U")
        }
      }
      true
    }

    // GitHub Release OTA：启动即检查（有更新在长生命周期页弹窗，同 tag 只弹一次）
    AppUpdater.checkLatest(this) { release, error ->
      if (isFinishing || isDestroyed) return@checkLatest
      if (release != null) {
        handler.post {
          if (isFinishing || isDestroyed) return@post
          AppUpdater.pending = release
          AppUpdater.offerUpdate(this@ScreenMirrorActivity)
        }
      } else if (error != null) {
        handler.post {
          if (isFinishing || isDestroyed) return@post
          Toast.makeText(this@ScreenMirrorActivity, error, Toast.LENGTH_SHORT).show()
        }
      }
    }

    // 浮动车控条
    findViewById<TextView>(R.id.btn_bar_lane_left).setOnClickListener {
      sendCarrotCmd("LANECHANGE", "LEFT")
    }
    findViewById<TextView>(R.id.btn_bar_speed_down).setOnClickListener {
      sendCarrotCmd("SPEED", "DOWN")
    }
    findViewById<TextView>(R.id.btn_bar_speed_up).setOnClickListener {
      sendCarrotCmd("SPEED", "UP")
    }
    findViewById<TextView>(R.id.btn_bar_lane_right).setOnClickListener {
      sendCarrotCmd("LANECHANGE", "RIGHT")
    }
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    startTouchChannel()
    startAutoConnect()
  }

  override fun onPause() {
    super.onPause()
    resumed = false
    player?.stop()
    connectThread?.interrupt()
    stopTouchChannel()
    // 若多设备选择弹窗仍在阻塞发现线程，立即释放，避免线程 leak/crash
    devicePickLatch?.countDown()
  }

  private fun hideSystemBars() {
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
      .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
  }

  @Deprecated("Deprecated in Java")
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideSystemBars()
  }

  // ---- 自动发现 + 自动连接状态机 ---- //

  private fun startAutoConnect() {
    connectThread?.interrupt()
    connectThread = Thread {
      var ip: String? = deviceIp
      while (resumed && !Thread.currentThread().isInterrupted) {
        // 阶段 1：发现设备（无 IP 时监听 7705 广播；多台设备弹选择）
        if (ip == null) {
          searchRounds++
          handler.post {
            setHint("正在搜寻 sunnypilot 设备…")
            if (searchRounds >= SEARCH_GUIDE_AFTER_ROUNDS) {
              retryButton.visibility = View.VISIBLE
            }
          }
          val devices = CarrotDiscovery.discoverMultiple(3)
          val chosen = when {
            devices.isEmpty() -> {
              if (searchRounds >= SEARCH_GUIDE_AFTER_ROUNDS) {
                handler.post {
                  setHint("持续未发现设备：请确认车机已开启「投屏/触控」参数，且与本机处于同一网络")
                }
              }
              null
            }
            devices.size == 1 -> devices[0]
            else -> askUserToPickDevice(devices)
          }
          if (chosen == null) {
            try { Thread.sleep(500) } catch (e: InterruptedException) { return@Thread }
            continue
          }
          searchRounds = 0
          handler.post { retryButton.visibility = View.GONE }
          ip = chosen.ip
          devicePort = chosen.carrotPort
        }
        // 阶段 2：连接（视频 + 触摸）
        val target = ip
        deviceIp = target
        UiPrefs.appendLog(this@ScreenMirrorActivity, "connect $target port=$devicePort")
        handler.post { setHint("已连接 $target，正在打开画面…") }
        startTouchChannel()
        handler.post { startVideo(target) }
        // 阶段 3：守护——出错则回到搜寻状态
        while (resumed && !streamError && !Thread.currentThread().isInterrupted) {
          try { Thread.sleep(500) } catch (e: InterruptedException) { return@Thread }
        }
        if (!resumed) return@Thread
        UiPrefs.appendLog(this@ScreenMirrorActivity, "disconnect $target")
        handler.post {
          setHint("连接中断，正在重新搜寻设备…")
          player?.stop()
        }
        stopTouchChannel()
        streamError = false
        ip = null
        deviceIp = null
        try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
      }
    }.also { it.isDaemon = true; it.start() }
  }

  /** 多设备时在 UI 线程弹选择框，阻塞发现线程直至用户选择 */
  private fun askUserToPickDevice(devices: List<CarrotDiscovery.Device>): CarrotDiscovery.Device? {
    val latch = CountDownLatch(1)
    // 保存到成员变量，Activity 销毁时可强制释放，避免发现线程永远阻塞
    devicePickLatch = latch
    chosenIp = null
    handler.post {
      if (isFinishing || isDestroyed) {
        latch.countDown()
        return@post
      }
      val labels = devices.map { d ->
        val road = if (d.onroad) " · 行车中" else ""
        "${d.ip}  ${d.version}$road"
      }
      AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("发现多台设备，请选择")
        .setItems(labels.toTypedArray()) { _, which ->
          chosenIp = devices[which].ip
          latch.countDown()
        }
        .setOnCancelListener { latch.countDown() }
        .setOnDismissListener { latch.countDown() }
        .show()
    }
    try {
      latch.await()
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    } finally {
      devicePickLatch = null
    }
    return devices.firstOrNull { it.ip == chosenIp }
  }

  private fun startVideo(ip: String) {
    val p = player ?: run {
      setHint("视频引擎未就绪")
      return
    }
    if (p.isPlaying) p.stop()
    val media = Media(libVlc, "tcp://$ip:$VIDEO_PORT")
    media.addOption(":network-caching=${UiPrefs.networkCachingMs(this)}")
    media.addOption(":no-audio")
    p.setMedia(media)
    hint.visibility = View.GONE
    retryButton.visibility = View.GONE
    p.play()
  }

  private fun setHint(text: String) {
    hint.visibility = View.VISIBLE
    hint.text = text
  }

  // ---- 浮动车控条（carrot UDP 指令） ---- //

  private fun sendCarrotCmd(cmd: String, arg: String) {
    val ip = deviceIp ?: run {
      handler.post { setHint("未连接设备，无法发送车控指令") }
      return
    }
    CommandClient.sendAsync(ip, devicePort, cmd, arg) { msg ->
      UiPrefs.appendLog(this@ScreenMirrorActivity, "cmd $cmd $arg -> $msg")
    }
  }

  // ---- 触点本地回显 ---- //

  private fun showTouchIndicator(rawX: Float, rawY: Float) {
    touchIndicator.visibility = View.VISIBLE
    touchIndicator.translationX = rawX - touchIndicator.width / 2f
    touchIndicator.translationY = rawY - touchIndicator.height / 2f
  }

  // ---- 触摸回传通道（TCP 7071，断线自动重连） ---- //

  private fun startTouchChannel() {
    val t = touchThread
    if (t != null && t.isAlive) return   // 已在运行，避免重复启动多个线程
    touchThread = Thread {
      var out: OutputStream? = null
      var running = true
      while (running) {
        val ip = deviceIp
        if (out == null) {
          if (ip == null) {
            try { Thread.sleep(500) } catch (e: InterruptedException) { running = false; break }
            continue
          }
          try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, TOUCH_PORT), 3000)
            s.tcpNoDelay = true
            touchSocket = s
            out = s.getOutputStream()
          } catch (e: Exception) {
            try { touchSocket?.close() } catch (_: Exception) {}
            touchSocket = null
            try { Thread.sleep(500) } catch (e2: InterruptedException) { running = false; break }
            continue
          }
        }
        val line = try {
          touchQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
          // 收到中断信号：恢复标志、退出循环
          Thread.currentThread().interrupt()
          running = false
          break
        }
        if (line == null) continue
        try {
          out.write((line + "\n").toByteArray(Charsets.UTF_8))
          out.flush()
        } catch (e: Exception) {
          try { touchSocket?.close() } catch (_: Exception) {}
          touchSocket = null
          out = null
        }
      }
      try { touchSocket?.close() } catch (_: Exception) {}
      touchSocket = null
    }.also { it.isDaemon = true; it.start() }
  }

  private fun stopTouchChannel() {
    touchThread?.interrupt()
    try { touchSocket?.close() } catch (_: Exception) {}
    touchSocket = null
    touchThread = null
  }

  private fun sendTouch(line: String) {
    if (!touchQueue.offer(line)) {
      touchQueue.clear()
      touchQueue.offer(line)
    }
  }

  override fun onNewVideoLayout(vout: IVLCVout?, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
    // 视频即完整 UI 画面，无需缩放裁剪
  }

  override fun onDestroy() {
    super.onDestroy()
    resumed = false
    connectThread?.interrupt()
    touchThread?.interrupt()
    devicePickLatch?.countDown()
    player?.getVLCVout()?.detachViews()
    player?.release()
    libVlc?.release()
    try { touchSocket?.close() } catch (_: Exception) {}
  }
}
