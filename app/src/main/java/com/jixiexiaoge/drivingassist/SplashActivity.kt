package com.jixiexiaoge.drivingassist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/** 启动页：品牌过场（S 图标），1.2s 后进入主界面 */
class SplashActivity : Activity() {

  private val handler = Handler(Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_splash)
    val splashMs = UiPrefs.splashDurationMs(this)
    findViewById<TextView>(R.id.splash_icon).background.setTint(UiPrefs.accentColor(this))
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
      .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
      .or(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    handler.postDelayed({
      startActivity(Intent(this, ScreenMirrorActivity::class.java))
      finish()
    }, splashMs)
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    handler.removeCallbacksAndMessages(null)
    super.onBackPressed()
  }
}
