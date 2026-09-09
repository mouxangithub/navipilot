package com.jixiexiaoge.drivingassist

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Release OTA：
 *
 * 发布约定（每次发版）：
 *   1. tag 命名为 r<versionCode>，且 versionCode 必须大于线上最新（如 r260801）；
 *   2. Release Assets 里上传 APK（取第一个 .apk 作为下载地址）；
 *   3. Release 说明(body)写更新内容，弹窗时展示。
 *
 * 网络策略（大陆直连 GitHub 不稳）：
 *   - API 检查按镜像列表轮询：直连 → ghfast.top → gh-proxy.com，成功的镜像会记住；
 *   - APK 下载走同一镜像前缀（Android 安装器会校验签名一致性，中转篡改无法覆盖安装）；
 *   - 全部失败时回调 error，调用方可见化提示。
 *
 * 流程：启动后台请求 releases/latest → tag 数字 > 本地 longVersionCode
 *   → 弹更新对话框 → DownloadManager 下载（带通知进度） → FileProvider 拉起系统安装器。
 */
object AppUpdater {

  /** OTA 发布仓库（改成你自己的仓库名即可） */
  const val REPO = "mouxangithub/navipilot"

  /** API/下载镜像前缀列表（依次尝试；空串=直连） */
  private val MIRRORS = listOf("", "https://ghfast.top/", "https://gh-proxy.com/")

  private const val PREFS = "sunny_ota"
  private const val KEY_MIRROR = "working_mirror"
  private const val CHANNEL_ID = "ota_update"
  private const val NOTIFICATION_ID = 0x4F54 // "OT"

  private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  /** 待提示的更新（检查通过 → 弹窗） */
  @Volatile
  var pending: Release? = null

  private var offeredTag: String? = null

  data class Release(val versionCode: Long, val apkUrl: String, val notes: String)

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun workingMirror(context: Context): String =
    prefs(context).getString(KEY_MIRROR, "") ?: ""

  private fun setWorkingMirror(context: Context, mirror: String) {
    prefs(context).edit().putString(KEY_MIRROR, mirror).apply()
  }

  /**
   * 后台检查最新 Release；结果回调在后台线程。
   * @param release 有可用更新时非空；error 非空时表示检查失败（网络原因）
   */
  fun checkLatest(context: Context, onResult: (Release?, String?) -> Unit) {
    Thread {
      var lastError: String? = null
      val mirrors = buildList {
        val last = workingMirror(context)
        if (last.isNotEmpty() && MIRRORS.contains(last)) add(last)
        addAll(MIRRORS.filter { it != last })
      }
      var release: Release? = null
      var goodMirror: String? = null
      for (mirror in mirrors) {
        val result = runCatching { fetchLatest(mirror) }
        release = result.getOrNull()
        if (release != null) {
          goodMirror = mirror
          break
        }
        lastError = result.exceptionOrNull()?.message ?: "http error"
      }
      if (goodMirror != null) setWorkingMirror(context, goodMirror)
      if (release == null) {
        UiPrefs.appendLog(context, "ota check failed: $lastError")
        onResult(null, "更新检查失败（网络）：$lastError")
        return@Thread
      }
      val local = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
      } catch (_: Exception) {
        0L
      }
      UiPrefs.appendLog(context, "ota check local=$local remote=${release.versionCode} mirror=${goodMirror ?: "direct"}")
      if (release.versionCode > local) onResult(release, null) else onResult(null, null)
    }.start()
  }

  private fun fetchLatest(mirrorPrefix: String): Release? {
    val request = Request.Builder()
      .url("${mirrorPrefix}https://api.github.com/repos/$REPO/releases/latest")
      .header("Accept", "application/vnd.github+json")
      .header("User-Agent", "sunnypilot-dazi-ota")
      .build()
    http.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
      val json = JSONObject(resp.body!!.string())
      val tag = json.optString("tag_name", "")
      val code = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toLongOrNull() ?: return null
      val assets = json.optJSONArray("assets") ?: return null
      var apkUrl = ""
      for (i in 0 until assets.length()) {
        val asset = assets.getJSONObject(i)
        val name = asset.optString("name", "")
        if (name.endsWith(".apk", ignoreCase = true)) {
          apkUrl = asset.optString("browser_download_url", "")
          break
        }
      }
      if (apkUrl.isEmpty()) return null
      val notes = json.optString("body", "").take(500)
      return Release(code, apkUrl, notes)
    }
  }

  /** 在 Activity 弹更新确认框（同一 tag 只弹一次） */
  fun offerUpdate(activity: Activity) {
    val release = pending ?: return
    if (offeredTag == "r${release.versionCode}") return
    offeredTag = "r${release.versionCode}"
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("发现新版本 r${release.versionCode}")
      .setMessage(release.notes.ifBlank { "下载并安装更新？" })
      .setPositiveButton("下载安装") { _, _ -> downloadAndInstall(activity, release) }
      .setNegativeButton("暂不", null)
      .show()
  }

  /** DownloadManager 下载 APK（自动带可用镜像前缀），完成后自动拉起安装器；带通知进度 */
  fun downloadAndInstall(context: Context, release: Release) {
    ensureNotificationChannel(context)
    val mirror = workingMirror(context)
    val url = mirror + release.apkUrl
    val apkName = "sunnypilot_dazi_r${release.versionCode}.apk"
    val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName)
    if (target.exists() && target.length() > 0) {
      installApk(context, target)
      return
    }
    Toast.makeText(context, "开始后台下载更新…", Toast.LENGTH_SHORT).show()
    val request = DownloadManager.Request(Uri.parse(url))
      .setTitle("sunnypilot搭子 r${release.versionCode}")
      .setDescription("下载完成后自动弹出安装")
      // VISIBILITY_VISIBLE：系统通知栏自带下载进度，无需额外权限
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      .setDestinationUri(Uri.fromFile(target))
      .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = dm.enqueue(request)

    val cancelled = AtomicBoolean(false)
    val progressRunnable = object : Runnable {
      override fun run() {
        if (cancelled.get()) return
        val (bytes, total, status) = queryProgress(dm, downloadId)
        when (status) {
          DownloadManager.STATUS_SUCCESSFUL -> {
            showNotification(context, "下载完成", "点击安装新版本", 100, true)
            if (target.exists() && target.length() > 0) {
              UiPrefs.appendLog(context, "ota downloaded r${release.versionCode} size=${target.length()} mirror=$mirror")
              installApk(context, target)
            }
            return
          }
          DownloadManager.STATUS_FAILED -> {
            showNotification(context, "下载失败", "请检查网络后重试", -1, false)
            UiPrefs.appendLog(context, "ota download failed r${release.versionCode} mirror=$mirror reason=${dm.getUriForDownloadedFile(downloadId)}")
            Toast.makeText(context, "更新下载失败，请检查网络后重试", Toast.LENGTH_LONG).show()
            return
          }
          else -> {
            val pct = if (total > 0) (bytes * 100 / total).toInt() else -1
            showNotification(context, "正在下载更新 r${release.versionCode}", formatBytes(bytes) + "/" + formatBytes(total), pct, false)
            Handler(Looper.getMainLooper()).postDelayed(this, 500)
          }
        }
      }
    }
    Handler(Looper.getMainLooper()).post(progressRunnable)

    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
        try { context.unregisterReceiver(this) } catch (_: Exception) {}
        cancelled.set(true)
      }
    }
    if (Build.VERSION.SDK_INT >= 33) {
      context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }
  }

  private fun queryProgress(dm: DownloadManager, id: Long): Triple<Long, Long, Int> {
    val q = DownloadManager.Query().setFilterById(id)
    dm.query(q)?.use { c ->
      if (c.moveToFirst()) {
        val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        return Triple(
          if (bytesIdx >= 0) c.getLong(bytesIdx) else 0L,
          if (totalIdx >= 0) c.getLong(totalIdx) else 0L,
          if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_PENDING
        )
      }
    }
    return Triple(0L, 0L, DownloadManager.STATUS_PENDING)
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
      size /= 1024
      unit++
    }
    return String.format("%.1f%s", size, units[unit])
  }

  private fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "应用更新",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "OTA 在线更新下载进度"
      setSound(null, null)
    }
    nm.createNotificationChannel(channel)
  }

  private fun showNotification(context: Context, title: String, text: String, progress: Int, indeterminateOrComplete: Boolean) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(progress in 0..99)
      .setOnlyAlertOnce(true)
    if (progress in 0..99) {
      builder.setProgress(100, progress, false)
    } else if (indeterminateOrComplete) {
      builder.setProgress(0, 0, false)
    }
    nm.notify(NOTIFICATION_ID, builder.build())
  }

  /** FileProvider + 系统安装器；Android 8+ 先引导未知来源授权 */
  private fun installApk(context: Context, apk: File) {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
      Toast.makeText(context, "请先允许本应用「安装未知应用」权限", Toast.LENGTH_LONG).show()
      try {
        context.startActivity(
          Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      } catch (_: Exception) {}
      return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }
}
