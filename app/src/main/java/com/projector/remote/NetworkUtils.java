package com.projector.remote;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    /**
     * 获取设备在局域网中的 IP 地址
     */
    public static String getLocalIpAddress(Context context) {
        // 优先通过 WiFi 获取
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi.getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xFF, (ip >> 8) & 0xFF,
                            (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WiFi IP lookup failed", e);
        }

        // 降级：遍历网络接口
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Network interface lookup failed", e);
        }

        return "0.0.0.0";
    }

    /**
     * 检查是否连接到 WiFi/以太网
     */
    public static boolean isNetworkConnected(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.getConnectionInfo() != null
                    && wm.getConnectionInfo().getNetworkId() != -1;
        } catch (Exception e) {
            return false;
        }
    }
}
