package com.mouxan.drivingassist;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 车控指令：UDP 发送 carrot 命令（与 Navipilot NetworkManager.sendControlCommand 同构）。
 *   SPEED UP/DOWN        — 设定时速 ±1 km/h
 *   SPEED SET <kph>      — 设定绝对时速
 *   LANECHANGE LEFT/RIGHT — 虚拟转向灯 → 自动变道（超车）
 * LANECHANGE 需连发 3 次（间隔 50ms）确保被 desire_helper 的 carrotCmdIndex 窗口捕获。
 */
public class CommandClient {

  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger(0);

  public interface ResultListener {
    void onResult(String message);
  }

  public static void sendAsync(String ip, int port, String cmd, String arg, ResultListener listener) {
    new Thread(() -> {
      try {
        int repeat = "LANECHANGE".equals(cmd) ? 3 : 1;
        for (int i = 0; i < repeat; i++) {
          send(ip, port, cmd, arg);
          if (i < repeat - 1) Thread.sleep(50);
        }
        listener.onResult("✓ " + cmd + " " + arg);
      } catch (Exception e) {
        listener.onResult("✗ " + cmd + " 发送失败: " + e.getMessage());
      }
    }, "carrot-cmd").start();
  }

  private static void send(String ip, int port, String cmd, String arg) throws Exception {
    JSONObject msg = new JSONObject();
    msg.put("carrotIndex", SEQ.incrementAndGet());
    msg.put("carrotCmd", cmd);
    msg.put("carrotArg", arg);
    byte[] data = msg.toString().getBytes("UTF-8");
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(ip), port));
    }
  }
}
