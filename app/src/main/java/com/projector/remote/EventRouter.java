package com.projector.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

public class EventRouter implements WebSocketServer.MessageCallback {

    private static final String TAG = "EventRouter";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebSocketServer wsServer;

    public void setWsServer(WebSocketServer server) {
        this.wsServer = server;
    }

    @Override
    public void onMessage(String message) {
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

    public void processMessage(String raw) {
        InputAccessibilityService svc = InputAccessibilityService.getInstance();
        if (svc == null) {
            Log.w(TAG, "Accessibility service not running, ignoring: " + raw);
            return;
        }

        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.getString("t");

            switch (type) {
                case "m":
                    int dx = msg.getInt("dx");
                    int dy = msg.getInt("dy");
                    svc.moveCursor(dx, dy);
                    break;

                case "c":
                    svc.click();
                    break;

                case "dc":
                    svc.doubleClick();
                    break;

                case "lc":
                    svc.longPress();
                    break;

                case "s": {
                    int sdy = msg.optInt("dy", 0);
                    int sdx = msg.optInt("dx", 0);
                    svc.scroll(sdx, sdy);
                    break;
                }

                case "ds": {
                    float gx = (float) msg.getDouble("x");
                    float gy = (float) msg.getDouble("y");
                    svc.setCursor(gx, gy);
                    svc.dragMove();
                    break;
                }

                case "dss": {
                    float sx = (float) msg.getDouble("x");
                    float sy = (float) msg.getDouble("y");
                    svc.setCursor(sx, sy);
                    svc.dragStart();
                    break;
                }

                case "de":
                    svc.dragEnd();
                    break;

                case "t":
                    String text = msg.getString("text");
                    svc.inputText(text);
                    break;

                case "t+":
                    String appendText = msg.getString("text");
                    svc.appendText(appendText);
                    break;

                case "bs":
                    svc.pressBackspace();
                    break;

                case "k": {
                    String key = msg.getString("key");
                    handleKey(svc, key);
                    break;
                }

                case "a": {
                    String action = msg.getString("action");
                    handleAction(svc, action);
                    break;
                }

                case "p":
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
