package com.mouxan.drivingassist

import android.content.Context

/**
 * 统一偏好与本地埋点：
 *  - 侧边栏开合记忆（功能补全 #4）
 *  - 投屏 network-caching 延迟（#6）
 *  - 主题色 green/blue（#8）
 *  - 启动页时长（#9）
 *  - 连接质量埋点 → 应用外部私有目录 connection_log.txt（#10）
 */
object UiPrefs {

  private const val PREFS = "sunny_ui_prefs"

  // ---- 键 ---- //
  private const val KEY_SIDEBAR_OPEN = "sidebar_open"
  private const val KEY_NETWORK_CACHING = "network_caching_ms"
  private const val KEY_THEME = "theme_color"
  private const val KEY_SPLASH_MS = "splash_duration_ms"

  const val CACHING_FAST = 150
  const val CACHING_BALANCED = 300
  const val CACHING_SMOOTH = 500

  /** 品牌强调色：跟随主题设置（绿 #34D399 / 蓝 #60A5FA） */
  fun accentColor(context: Context): Int =
    if (themeColor(context) == "blue") 0xFF60A5FA.toInt() else 0xFF34D399.toInt()

  fun themeColor(context: Context): String =
    prefs(context).getString(KEY_THEME, "green") ?: "green"

  fun setThemeColor(context: Context, value: String) {
    prefs(context).edit().putString(KEY_THEME, value).apply()
  }

  fun networkCachingMs(context: Context): Int =
    prefs(context).getInt(KEY_NETWORK_CACHING, CACHING_FAST)

  fun setNetworkCachingMs(context: Context, value: Int) {
    prefs(context).edit().putInt(KEY_NETWORK_CACHING, value).apply()
  }

  fun splashDurationMs(context: Context): Long =
    prefs(context).getLong(KEY_SPLASH_MS, 1200L)

  fun setSplashDurationMs(context: Context, value: Long) {
    prefs(context).edit().putLong(KEY_SPLASH_MS, value).apply()
  }

  fun sidebarOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_SIDEBAR_OPEN, false)

  fun setSidebarOpen(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_SIDEBAR_OPEN, value).apply()
  }

  /** 连接质量埋点：追加一行时间戳日志（外部私有目录，卸载即清） */
  fun appendLog(context: Context, line: String) {
    try {
      val dir = context.getExternalFilesDir(null) ?: return
      java.io.File(dir, "connection_log.txt").appendText(
        "${System.currentTimeMillis()} $line\n"
      )
    } catch (_: Exception) {
    }
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
