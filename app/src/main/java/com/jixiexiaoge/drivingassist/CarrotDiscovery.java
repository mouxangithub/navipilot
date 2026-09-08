package com.jixiexiaoge.drivingassist;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * 自动发现：监听 openpilot 设备 carrot_man 的 UDP 7705 状态广播（每 2 秒一帧），
 * 与 Navipilot App 的发现机制一致 —— 无需配对、无需凭据。
 */
public class CarrotDiscovery {

  /** 发现结果：设备 ip / 指令端口（CarrotManUdpPort）/ 行车状态 / 版本 */
  public static class Device {
    public final String ip;
    public final int carrotPort;
    public final boolean onroad;
    public final String version;
    public final long seenAtMs = android.os.SystemClock.uptimeMillis();

    public Device(String ip, int carrotPort, boolean onroad, String version) {
      this.ip = ip;
      this.carrotPort = carrotPort;
      this.onroad = onroad;
      this.version = version;
    }
  }

  public interface Listener {
    /** 在后台线程回调；如需更新 UI 请自行 post 到主线程 */
    void onDevice(Device device);
  }

  public static Device discoverOnce(int timeoutSeconds) {
    try (DatagramSocket socket = new DatagramSocket(7705)) {
      socket.setReuseAddress(true);
      socket.setSoTimeout(1000);
      byte[] buf = new byte[4096];
      long deadline = android.os.SystemClock.uptimeMillis() + timeoutSeconds * 1000L;
      while (android.os.SystemClock.uptimeMillis() < deadline) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
          socket.receive(packet);
          JSONObject json = new JSONObject(new String(packet.getData(), 0, packet.getLength(), "UTF-8"));
          String ip = packet.getAddress().getHostAddress();
          return new Device(ip, json.optInt("port", 43001), json.optBoolean("IsOnroad", false),
              json.optString("Carrot2", ""));
        } catch (java.net.SocketTimeoutException e) {
          // keep waiting
        } catch (Exception e) {
          // malformed packet, keep listening
        }
      }
    } catch (Exception e) {
      android.util.Log.w("SunnyDazi", "discovery failed: " + e);
    }
    return null;
  }

  /** 多设备发现：监听 seconds 秒，按 IP 去重（用于同网多台设备的选择列表） */
  public static java.util.List<Device> discoverMultiple(int seconds) {
    java.util.LinkedHashMap<String, Device> found = new java.util.LinkedHashMap<>();
    try (DatagramSocket socket = new DatagramSocket(7705)) {
      socket.setReuseAddress(true);
      socket.setSoTimeout(1000);
      byte[] buf = new byte[4096];
      long deadline = android.os.SystemClock.uptimeMillis() + seconds * 1000L;
      while (android.os.SystemClock.uptimeMillis() < deadline) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
          socket.receive(packet);
          JSONObject json = new JSONObject(new String(packet.getData(), 0, packet.getLength(), "UTF-8"));
          String ip = packet.getAddress().getHostAddress();
          found.put(ip, new Device(ip, json.optInt("port", 43001),
              json.optBoolean("IsOnroad", false), json.optString("Carrot2", "")));
        } catch (java.net.SocketTimeoutException e) {
          // keep waiting
        } catch (Exception e) {
          // malformed packet, keep listening
        }
      }
    } catch (Exception e) {
      android.util.Log.w("SunnyDazi", "multi discovery failed: " + e);
    }
    return new java.util.ArrayList<>(found.values());
  }
}
