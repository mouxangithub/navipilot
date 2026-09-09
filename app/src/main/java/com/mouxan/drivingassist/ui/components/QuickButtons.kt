package com.mouxan.drivingassist.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mouxan.drivingassist.CarrotParamClient
import com.mouxan.drivingassist.UiPrefs
import com.mouxan.drivingassist.ScreenMirrorActivity
import kotlinx.coroutines.launch

/**
 * 常驻快捷行 —— 侧边栏显隐/排序持久化 + 两档场景模式（完整/驾驶）。
 *
 * - 「✏️」chip 打开编辑对话框：逐钮勾选显隐、上下调整顺序；
 * - 「驾驶模式」只保留防分心最小集（静音/显示切换）；
 * - 配置持久化到 SharedPreferences（sunny_quick_buttons）。
 */
object QuickButtonStore {
  private const val PREFS = "sunny_quick_buttons"
  const val MODE_FULL = 0
  const val MODE_DRIVING = 1

  /** 全部可配置的快捷钮：id -> 显示名 */
  val ALL: List<Pair<String, String>> = listOf(
    "mute" to "静音",
    "display" to "显示",
    "mirror" to "投屏",
    "search" to "搜索",
    "navconf" to "确认",
  )

  data class Config(val mode: Int, val order: List<String>, val hidden: Set<String>)

  fun load(context: Context): Config {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val mode = prefs.getInt("mode", MODE_FULL)
    val order = prefs.getString("order", null)?.split(",")?.filter { it.isNotBlank() }
      ?: ALL.map { it.first }
    val hidden = prefs.getString("hidden", "")?.split(",")?.filter { it.isNotBlank() }?.toSet()
      ?: emptySet()
    // 兜底：把新增按钮并入 order 尾部
    val merged = order + ALL.map { it.first }.filter { it !in order }
    return Config(mode, merged, hidden)
  }

  fun save(context: Context, config: Config) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putInt("mode", config.mode)
      .putString("order", config.order.joinToString(","))
      .putString("hidden", config.hidden.joinToString(","))
      .apply()
  }

  fun visibleButtons(config: Config): List<String> {
    if (config.mode == MODE_DRIVING) return listOf("mute", "display")
    return config.order.filter { it !in config.hidden }
  }
}

@Composable
fun QuickButtonsRow(
  carrotParamClient: CarrotParamClient?,
  getDeviceIp: () -> String?,
  onDisplayCommand: (String) -> Unit,
  onSearchClick: () -> Unit,
  onSendNavConfirmation: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var config by remember { mutableStateOf(QuickButtonStore.load(context)) }
  var showEditor by remember { mutableStateOf(false) }
  var muteOn by remember { mutableStateOf(false) }
  var showMap by remember { mutableStateOf(true) }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    val items = QuickButtonStore.visibleButtons(config)
    for (id in items) {
      val (label, bg, fg) = when (id) {
        "mute" -> Triple(if (muteOn) "🔇" else "🔊", Color(0xFF1B232D), Color(0xFF98A6B3))
        "display" -> Triple(if (showMap) "🗺️" else "🛣️", Color(0xFF1B232D), Color(0xFF98A6B3))
        "mirror" -> Triple("投屏", Color(0xFF12321F), Color(0xFF34D399))
        "search" -> Triple("🔍", Color(0xFF1B232D), Color(0xFF98A6B3))
        "navconf" -> Triple("确认", Color(0xFF1B232D), Color(0xFF98A6B3))
        else -> Triple(id, Color(0xFF1B232D), Color(0xFF98A6B3))
      }
      QuickChip(label, bg, fg, Modifier.weight(1f)) {
        when (id) {
          "mute" -> {
            val client = carrotParamClient
            if (client == null) return@QuickChip
            scope.launch {
              val cur = client.getParams("SoundVolumeAdjust")
                .getOrNull()?.get("SoundVolumeAdjust")?.let { (it as? Number)?.toInt() } ?: 100
              val target = if (cur > 0) 0 else 100
              client.setParam("SoundVolumeAdjust", target)
              muteOn = target == 0
            }
          }
          "display" -> {
            showMap = !showMap
            // DISPLAY MAP/ROAD —— carrot_serv 已支持的显示切换
            onDisplayCommand(if (showMap) "MAP" else "ROAD")
          }
          "mirror" -> {
            context.startActivity(
              Intent(context, ScreenMirrorActivity::class.java)
                .putExtra("device_ip", getDeviceIp())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
          "search" -> onSearchClick()
          "navconf" -> onSendNavConfirmation()
        }
      }
    }
    // 编辑入口
    QuickChip("✏️", Color(0xFF263140), Color(0xFF98A6B3), Modifier.width(44.dp)) {
      showEditor = true
    }
  }

  if (showEditor) {
    QuickButtonEditorDialog(
      config = config,
      onDismiss = { showEditor = false },
      onApply = {
        config = it
        QuickButtonStore.save(context, it)
        showEditor = false
      }
    )
  }
}

@Composable
private fun QuickChip(label: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(
    modifier = modifier
      .height(40.dp)
      .background(bg, RoundedCornerShape(12.dp))
      .clickable { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
  }
}

/** 编辑对话框：两档模式 + 逐钮显隐 + 上下排序 */
@Composable
private fun QuickButtonEditorDialog(
  config: QuickButtonStore.Config,
  onDismiss: () -> Unit,
  onApply: (QuickButtonStore.Config) -> Unit,
) {
  var draft by remember { mutableStateOf(config) }
  val context = LocalContext.current

  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    androidx.compose.material3.Card(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      shape = RoundedCornerShape(16.dp),
      colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF141A21))
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("快捷按钮设置", color = Color(0xFFECF2F8), fontSize = 16.sp, fontWeight = FontWeight.Bold)

        // 场景模式
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(
            QuickButtonStore.MODE_DRIVING to "驾驶模式",
            QuickButtonStore.MODE_FULL to "完整模式",
          ).forEach { (mode, label) ->
            val selected = draft.mode == mode
            Box(
              modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .background(
                  if (selected) Color(0xFF12321F) else Color(0xFF1B232D),
                  RoundedCornerShape(10.dp)
                )
                .border(1.dp, if (selected) Color(0xFF34D399) else Color(0xFF263140), RoundedCornerShape(10.dp))
                .clickable { draft = draft.copy(mode = mode) },
              contentAlignment = Alignment.Center
            ) {
              Text(label, color = if (selected) Color(0xFF34D399) else Color(0xFF98A6B3), fontSize = 13.sp)
            }
          }
        }
        if (draft.mode == QuickButtonStore.MODE_DRIVING) {
          Text("驾驶模式：仅保留静音与显示切换，防止行车分心", color = Color(0xFF5C6975), fontSize = 11.sp)
        }

        // ---- 投屏与外观设置（立即生效） ----
        val accentOn = Color(0xFF12321F)
        val accentFg = Color(0xFF34D399)
        Text("投屏与外观", color = Color(0xFF98A6B3), fontSize = 12.sp, fontWeight = FontWeight.Medium)

        // 检查更新（手动入口）
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("软件更新", color = Color(0xFFECF2F8), fontSize = 13.sp, modifier = Modifier.weight(1f))
          Box(
            modifier = Modifier
              .background(accentOn, RoundedCornerShape(8.dp))
              .clickable {
                com.mouxan.drivingassist.AppUpdater.checkLatest(context) { release, error ->
                  (context as? android.app.Activity)?.let { act ->
                    act.runOnUiThread {
                      when {
                        release != null -> {
                          com.mouxan.drivingassist.AppUpdater.pending = release
                          com.mouxan.drivingassist.AppUpdater.offerUpdate(act)
                        }
                        error != null -> android.widget.Toast.makeText(act, error, android.widget.Toast.LENGTH_LONG).show()
                        else -> android.widget.Toast.makeText(act, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                      }
                    }
                  }
                }
              }
              .padding(horizontal = 12.dp, vertical = 4.dp)
          ) { Text("检查", color = accentFg, fontSize = 12.sp) }
        }

        // 主题色
        var theme by remember { mutableStateOf(UiPrefs.themeColor(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("主题", color = Color(0xFFECF2F8), fontSize = 13.sp, modifier = Modifier.weight(1f))
          listOf("green" to "绿", "blue" to "蓝").forEach { (value, label) ->
            val selected = theme == value
            Box(
              modifier = Modifier
                .padding(start = 6.dp)
                .background(if (selected) accentOn else Color(0xFF1B232D), RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) accentFg else Color(0xFF263140), RoundedCornerShape(8.dp))
                .clickable { theme = value; UiPrefs.setThemeColor(context, value) }
                .padding(horizontal = 12.dp, vertical = 4.dp)
            ) { Text(label, color = if (selected) accentFg else Color(0xFF98A6B3), fontSize = 12.sp) }
          }
        }

        // 画面延迟
        var caching by remember { mutableStateOf(UiPrefs.networkCachingMs(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("画面延迟", color = Color(0xFFECF2F8), fontSize = 13.sp, modifier = Modifier.weight(1f))
          listOf(150 to "流畅", 300 to "均衡", 500 to "顺滑").forEach { (value, label) ->
            val selected = caching == value
            Box(
              modifier = Modifier
                .padding(start = 6.dp)
                .background(if (selected) accentOn else Color(0xFF1B232D), RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) accentFg else Color(0xFF263140), RoundedCornerShape(8.dp))
                .clickable { caching = value; UiPrefs.setNetworkCachingMs(context, value) }
                .padding(horizontal = 10.dp, vertical = 4.dp)
            ) { Text(label, color = if (selected) accentFg else Color(0xFF98A6B3), fontSize = 12.sp) }
          }
        }

        // 启动页时长
        var splash by remember { mutableStateOf(UiPrefs.splashDurationMs(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("启动页", color = Color(0xFFECF2F8), fontSize = 13.sp, modifier = Modifier.weight(1f))
          listOf(800L to "0.8s", 1200L to "1.2s", 2000L to "2s").forEach { (value, label) ->
            val selected = splash == value
            Box(
              modifier = Modifier
                .padding(start = 6.dp)
                .background(if (selected) accentOn else Color(0xFF1B232D), RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) accentFg else Color(0xFF263140), RoundedCornerShape(8.dp))
                .clickable { splash = value; UiPrefs.setSplashDurationMs(context, value) }
                .padding(horizontal = 10.dp, vertical = 4.dp)
            ) { Text(label, color = if (selected) accentFg else Color(0xFF98A6B3), fontSize = 12.sp) }
          }
        }

        // 逐钮显隐 + 排序（完整模式生效）
        QuickButtonStore.ALL.forEach { (id, name) ->
          val visible = id !in draft.hidden
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color(0xFFECF2F8), fontSize = 14.sp, modifier = Modifier.weight(1f))
            EditorChip("↑", enabled = draft.order.indexOf(id) > 0) {
              val idx = draft.order.indexOf(id)
              if (idx > 0) {
                val newOrder = draft.order.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                draft = draft.copy(order = newOrder)
              }
            }
            Spacer(Modifier.width(4.dp))
            EditorChip("↓", enabled = draft.order.indexOf(id) < draft.order.size - 1) {
              val idx = draft.order.indexOf(id)
              if (idx < draft.order.size - 1) {
                val newOrder = draft.order.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                draft = draft.copy(order = newOrder)
              }
            }
            Spacer(Modifier.width(4.dp))
            Box(
              modifier = Modifier
                .size(22.dp)
                .background(if (visible) Color(0xFF12321F) else Color(0xFF263140), CircleShape)
                .border(1.dp, if (visible) Color(0xFF34D399) else Color(0xFF475569), CircleShape)
                .clickable {
                  draft = if (visible) draft.copy(hidden = draft.hidden + id)
                  else draft.copy(hidden = draft.hidden - id)
                },
              contentAlignment = Alignment.Center
            ) {
              Text(if (visible) "✓" else "", color = Color(0xFF34D399), fontSize = 12.sp)
            }
          }
        }
        Text("顺序与显隐在完整模式下生效", color = Color(0xFF5C6975), fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(
            modifier = Modifier.weight(1f).height(40.dp)
              .background(Color(0xFF1B232D), RoundedCornerShape(10.dp))
              .clickable { onDismiss() },
            contentAlignment = Alignment.Center
          ) { Text("取消", color = Color(0xFF98A6B3), fontSize = 14.sp) }
          Box(
            modifier = Modifier.weight(1f).height(40.dp)
              .background(Color(0xFF34D399), RoundedCornerShape(10.dp))
              .clickable { onApply(draft) },
            contentAlignment = Alignment.Center
          ) { Text("应用", color = Color(0xFF06281A), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
      }
    }
  }
}

@Composable
private fun EditorChip(label: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(26.dp)
      .background(if (enabled) Color(0xFF1B232D) else Color(0xFF141A21), RoundedCornerShape(8.dp))
      .clickable(enabled = enabled) { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Text(label, color = if (enabled) Color(0xFFECF2F8) else Color(0xFF475569), fontSize = 12.sp)
  }
}
