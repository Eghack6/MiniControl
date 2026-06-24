package com.projector.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 事件路由器：解析 WebSocket 消息，调用 AccessibilityService 执行操作。
 *
 * 协议格式（JSON）：
 *   {"t":"m","dx":5,"dy":-3}       — 鼠标相对移动
 *   {"t":"c"}                       — 单击
 *   {"t":"dc"}                      — 双击
 *   {"t":"lc"}                      — 长按
 *   {"t":"s","dy":1}                — 滚轮 (dy>0向下)
 *   {"t":"ds","dx":10,"dy":20}      — 拖拽开始/移动 (归一化坐标)
 *   {"t":"de"}                      — 拖拽结束
 *   {"t":"t","text":"hello"}        — 输入文本
 *   {"t":"k","key":"backspace"}     — 按键
 *   {"t":"a","action":"back"}       — 全局动作 (back/home/recents)
 *   {"t":"p"}                       — Ping (客户端心跳)
 *
 * 所有消息处理在主线程，避免跨线程问题。
 */
public class EventRouter implements WebSocketServer.MessageCallback {

    private static final String TAG = "EventRouter";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebSocketServer wsServer;

    public void setWsServer(WebSocketServer server) {
        this.wsServer = server;
    }

    @Override
    public void onMessage(String message) {
        // 在主线程处理，确保 AccessibilityService 调用安全
        mainHandler.post(() -> processMessage(message));
    }

    @Override
    public void onClientConnected(String remoteAddress) {
        Log.i(TAG, "Client connected: " + remoteAddress);
    }

    @Override
    public void onClientDisconnected(String remoteAddress) {
        Log.i(TAG, "Client disconnected: " + remoteAddress);
    }

    private void processMessage(String raw) {
        InputAccessibilityService svc = InputAccessibilityService.getInstance();
        if (svc == null) {
            Log.w(TAG, "Accessibility service not running, ignoring: " + raw);
            return;
        }

        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.getString("t");

            switch (type) {
                case "m": // 鼠标移动
                    int dx = msg.getInt("dx");
                    int dy = msg.getInt("dy");
                    svc.moveCursor(dx, dy);
                    break;

                case "c": // 单击
                    svc.click();
                    break;

                case "dc": // 双击
                    svc.doubleClick();
                    break;

                case "lc": // 长按
                    svc.longPress();
                    break;

                case "s": { // 滚轮
                    int sdy = msg.optInt("dy", 0);
                    int sdx = msg.optInt("dx", 0);
                    svc.scroll(sdx, sdy);
                    break;
                }

                case "ds": { // 拖拽移动
                    float gx = (float) msg.getDouble("x");
                    float gy = (float) msg.getDouble("y");
                    svc.setCursor(gx, gy);
                    svc.dragMove();
                    break;
                }

                case "dss": { // 拖拽开始
                    float sx = (float) msg.getDouble("x");
                    float sy = (float) msg.getDouble("y");
                    svc.setCursor(sx, sy);
                    svc.dragStart();
                    break;
                }

                case "de": // 拖拽结束
                    svc.dragEnd();
                    break;

                case "t": // 文本输入
                    String text = msg.getString("text");
                    svc.inputText(text);
                    break;

                case "t+": // 追加文本
                    String appendText = msg.getString("text");
                    svc.appendText(appendText);
                    break;

                case "bs": // 退格
                    svc.pressBackspace();
                    break;

                case "k": { // 按键
                    String key = msg.getString("key");
                    handleKey(svc, key);
                    break;
                }

                case "a": { // 全局动作
                    String action = msg.getString("action");
                    handleAction(svc, action);
                    break;
                }

                case "p": // 心跳 → 回复 pong
                    if (wsServer != null) {
                        wsServer.broadcast("{\"t\":\"pong\"}");
                    }
                    break;

                default:
                    Log.w(TAG, "Unknown event type: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid message: " + raw, e);
        }
    }

    private void handleKey(InputAccessibilityService svc, String key) {
        switch (key) {
            case "enter":
                svc.pressKey(android.view.KeyEvent.KEYCODE_ENTER);
                break;
            case "backspace":
                svc.pressBackspace();
                break;
            case "tab":
                svc.pressKey(android.view.KeyEvent.KEYCODE_TAB);
                break;
            case "escape":
                svc.pressKey(android.view.KeyEvent.KEYCODE_ESCAPE);
                break;
            case "arrow_up":
                svc.pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP);
                break;
            case "arrow_down":
                svc.pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                break;
            default:
                // 单字符输入
                if (key.length() == 1) {
                    svc.inputText(key);
                }
                break;
        }
    }

    private void handleAction(InputAccessibilityService svc, String action) {
        switch (action) {
            case "back":
                svc.pressBack();
                break;
            case "home":
                svc.pressHome();
                break;
            case "recents":
                svc.pressRecentApps();
                break;
        }
    }
}
