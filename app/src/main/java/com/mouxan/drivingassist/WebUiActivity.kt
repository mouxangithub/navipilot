package com.mouxan.drivingassist

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch

/**
 * WebUiActivity：通过 WebView 1:1 加载车机 webui（http://<device-ip>:5080）。
 *
 * 设计要点：
 *  - 界面、样式、交互与原生 webui 完全一致（同一套前端代码）；
 *  - 启动即自动发现设备（UDP 7705 广播，多台设备弹选择）；
 *  - 支持 JavaScript 接口，实现原生返回键、错误提示、主题色同步；
 *  - 与 MainActivity 原生界面并存：WebView 用于完整 webui，原生用于高频操作。
 */
class WebUiActivity : Activity() {

  companion object {
    private const val WEBUI_PORT = 5080
    private const val SEARCH_GUIDE_AFTER_ROUNDS = 5
  }

  private val handler = Handler(Looper.getMainLooper())
  private lateinit var webView: WebView
  private lateinit var hint: TextView
  private lateinit var retryButton: TextView
  private var deviceIp: String? = null
  private var searchRounds = 0
  private var connectThread: Thread? = null

  @Volatile
  private var resumed = false

  @Volatile
  private var chosenIp: String? = null
  private var devicePickLatch: CountDownLatch? = null

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_webui)
    hideSystemBars()

    webView = findViewById(R.id.webui_webview)
    hint = findViewById(R.id.webui_hint)
    retryButton = findViewById(R.id.webui_btn_retry)
    deviceIp = intent.getStringExtra("device_ip")

    retryButton.setOnClickListener {
      retryButton.visibility = View.GONE
      searchRounds = 0
      connectThread?.interrupt()
      startAutoConnect()
    }

    // WebView 配置：与浏览器一致，支持 webui 所有功能
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      mediaPlaybackRequiresUserGesture = false
      allowFileAccess = true
      allowContentAccess = true
      cacheMode = WebSettings.LOAD_DEFAULT
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      useWideViewPort = true
      loadWithOverviewMode = true
      builtInZoomControls = false
      displayZoomControls = false
    }

    // JavaScript 接口：原生与 Web 交互
    webView.addJavascriptInterface(WebUiJsBridge(), "AndroidNative")

    webView.webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        handler.post { setHint("正在加载 webui…") }
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        handler.post {
          hint.visibility = View.GONE
          retryButton.visibility = View.GONE
        }
        // 注入主题色和返回键支持
        view?.evaluateJavascript(
          "window.AndroidNative && window.AndroidNative.onPageReady && window.AndroidNative.onPageReady();",
          null
        )
      }

      override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        handler.post {
          setHint("加载失败：${error?.description ?: "网络错误"}")
          retryButton.visibility = View.VISIBLE
        }
      }
    }

    webView.webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (newProgress < 80) {
          handler.post { setHint("正在加载 webui… $newProgress%") }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    startAutoConnect()
  }

  override fun onPause() {
    super.onPause()
    resumed = false
    connectThread?.interrupt()
    devicePickLatch?.countDown()
  }

  override fun onDestroy() {
    super.onDestroy()
    resumed = false
    connectThread?.interrupt()
    devicePickLatch?.countDown()
    webView.destroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
  }

  private fun hideSystemBars() {
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
      .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
      .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
  }

  private fun setHint(text: String) {
    hint.visibility = View.VISIBLE
    hint.text = text
  }

  // ---- 自动发现 + 自动连接 ---- //

  private fun startAutoConnect() {
    connectThread?.interrupt()
    connectThread = Thread {
      var ip: String? = deviceIp
      while (resumed && !Thread.currentThread().isInterrupted) {
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
        }
        val target = ip
        deviceIp = target
        UiPrefs.appendLog(this@WebUiActivity, "webui connect $target")
        handler.post {
          setHint("已连接 $target，正在打开 webui…")
          webView.loadUrl("http://$target:$WEBUI_PORT")
        }
        // 守护：断线则回到搜寻
        while (resumed && !Thread.currentThread().isInterrupted) {
          try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
        }
        if (!resumed) return@Thread
        UiPrefs.appendLog(this@WebUiActivity, "webui disconnect $target")
        ip = null
        deviceIp = null
        try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
      }
    }.also { it.isDaemon = true; it.start() }
  }

  private fun askUserToPickDevice(devices: List<CarrotDiscovery.Device>): CarrotDiscovery.Device? {
    val latch = CountDownLatch(1)
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

  /** JavaScript 接口：供 webui 调用原生功能 */
  inner class WebUiJsBridge {
    @JavascriptInterface
    fun onPageReady() {
      // webui 页面加载完成回调，可用于同步主题色、隐藏加载提示等
    }

    @JavascriptInterface
    fun close() {
      handler.post { finish() }
    }

    @JavascriptInterface
    fun toast(message: String) {
      handler.post { Toast.makeText(this@WebUiActivity, message, Toast.LENGTH_SHORT).show() }
    }
  }
}
