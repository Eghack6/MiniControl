package com.projector.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：将远程触控事件转化为本地鼠标操作。
 *
 * 能力矩阵：
 *   API 24+ : dispatchGesture() — 完整鼠标移动、点击、长按、拖拽、滚轮
 *   API 19-23: 有限支持 — 全局动作(返回/Home/最近)、节点点击、文本输入
 */
public class InputAccessibilityService extends AccessibilityService {

    private static final String TAG = "InputA11y";
    private static InputAccessibilityService instance;

    // 屏幕尺寸（像素）
    private int screenWidth;
    private int screenHeight;

    // 当前鼠标位置（归一化坐标 0.0-1.0）
    private float cursorX = 0.5f;
    private float cursorY = 0.5f;

    public static InputAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        updateScreenSize();
        Log.i(TAG, "Accessibility service connected, API=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // 不需要处理无障碍事件
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public void updateScreenSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        Log.i(TAG, "Screen size: " + screenWidth + "x" + screenHeight);
    }

    // ==================== 鼠标移动 ====================

    /**
     * 相对移动鼠标（增量，像素单位）
     */
    public void moveCursor(int dx, int dy) {
        cursorX += (float) dx / screenWidth;
        cursorY += (float) dy / screenHeight;
        cursorX = Math.max(0f, Math.min(1f, cursorX));
        cursorY = Math.max(0f, Math.min(1f, cursorY));
    }

    /**
     * 绝对定位鼠标（归一化坐标 0.0-1.0）
     */
    public void setCursor(float x, float y) {
        cursorX = Math.max(0f, Math.min(1f, x));
        cursorY = Math.max(0f, Math.min(1f, y));
    }

    // ==================== 点击操作 ====================

    /**
     * 在当前位置执行点击
     */
    public void click() {
        clickAt(cursorX, cursorY);
    }

    /**
     * 在指定位置点击（归一化坐标）
     */
    public void clickAt(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchTap(x, y);
        } else {
            clickNodeAt(x, y);
        }
    }

    /**
     * 双击
     */
    public void doubleClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            float x = cursorX, y = cursorY;
            dispatchTap(x, y);
            // 间隔 100ms 发第二次点击
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                dispatchTap(x, y);
            }, 100);
        } else {
            click();
        }
    }

    /**
     * 长按（按住指定时长后松开）
     */
    public void longPress() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchLongPress(cursorX, cursorY);
        } else {
            // 降级：尝试找到节点并执行长按
            AccessibilityNodeInfo node = findNodeAt(cursorX, cursorY);
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                node.recycle();
            }
        }
    }

    // ==================== 滚轮 ====================

    /**
     * 滚动（dy > 0 向下，dy < 0 向上）
     */
    public void scroll(int dx, int dy) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchScroll(dx, dy);
        } else {
            // 降级：对当前焦点节点执行滚动动作
            if (dy > 0) {
                performGlobalAction(GLOBAL_ACTION_SCROLL_FORWARD);
            } else if (dy < 0) {
                performGlobalAction(GLOBAL_ACTION_SCROLL_BACKWARD);
            }
        }
    }

    // ==================== 拖拽 ====================

    /**
     * 从当前位置开始拖拽（按下）
     */
    private float dragStartX, dragStartY;
    private boolean dragging = false;

    public void dragStart() {
        dragStartX = cursorX;
        dragStartY = cursorY;
        dragging = true;
    }

    /**
     * 拖拽移动到当前位置
     */
    public void dragMove() {
        if (!dragging || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        dispatchSwipe(dragStartX, dragStartY, cursorX, cursorY, 50);
        dragStartX = cursorX;
        dragStartY = cursorY;
    }

    /**
     * 拖拽结束（松开）
     */
    public void dragEnd() {
        dragging = false;
    }

    // ==================== 全局动作 ====================

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void pressRecentApps() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    // ==================== 键盘输入 ====================

    /**
     * 输入文本（通过当前焦点输入框）
     */
    public void inputText(String text) {
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (focused != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
            Log.d(TAG, "Input text: " + text);
        } else {
            Log.w(TAG, "No focused editable node for text input");
        }
    }

    /**
     * 追加文本到当前焦点
     */
    public void appendText(String text) {
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (focused != null) {
            String current = "";
            if (focused.getText() != null) {
                current = focused.getText().toString();
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
        }
    }

    /**
     * 删除一个字符（退格）
     */
    public void pressBackspace() {
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (focused != null && focused.getText() != null) {
            String current = focused.getText().toString();
            if (current.length() > 0) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current.substring(0, current.length() - 1));
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            focused.recycle();
        }
    }

    /**
     * 模拟按键事件（API 24+）
     */
    public void pressKey(int keyCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchKey(keyCode);
        }
    }

    // ==================== 内部实现 ====================

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private void dispatchTap(float normX, float normY) {
        float px = normX * screenWidth;
        float py = normY * screenHeight;

        Path path = new Path();
        path.moveTo(px, py);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        dispatchGesture(gesture, null, null);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private void dispatchLongPress(float normX, float normY) {
        float px = normX * screenWidth;
        float py = normY * screenHeight;

        Path path = new Path();
        path.moveTo(px, py);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 800);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        dispatchGesture(gesture, null, null);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private void dispatchSwipe(float fromX, float fromY, float toX, float toY, int durationMs) {
        Path path = new Path();
        path.moveTo(fromX * screenWidth, fromY * screenHeight);
        path.lineTo(toX * screenWidth, toY * screenHeight);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        dispatchGesture(gesture, null, null);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private void dispatchScroll(int dx, int dy) {
        float px = cursorX * screenWidth;
        float py = cursorY * screenHeight;

        // 滚轮模拟：从当前位置做一个短距离滑动
        Path path = new Path();
        path.moveTo(px, py);
        path.lineTo(px - dx * 2, py - dy * 3); // 缩放因子

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 200);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        dispatchGesture(gesture, null, null);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private void dispatchKey(int keyCode) {
        // 使用 dispatchGesture 无法发送按键，需要其他方式
        // 对于特殊键（回车、方向键等），通过全局动作实现
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                // 回车：尝试对焦点节点执行点击
                AccessibilityNodeInfo focused = findFocusedEditable();
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    focused.recycle();
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                pressBackspace();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                performGlobalAction(GLOBAL_ACTION_SCROLL_BACKWARD);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                performGlobalAction(GLOBAL_ACTION_SCROLL_FORWARD);
                break;
        }
    }

    /**
     * 尝试在归一化坐标处找到可点击节点并执行点击（API 19 降级方案）
     */
    private void clickNodeAt(float normX, float normY) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        int targetX = (int) (normX * screenWidth);
        int targetY = (int) (normY * screenHeight);

        AccessibilityNodeInfo node = findClickableAt(root, targetX, targetY);
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            node.recycle();
        }
        root.recycle();
    }

    private AccessibilityNodeInfo findClickableAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (!rect.contains(x, y)) return null;

        if (node.isClickable()) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findClickableAt(child, x, y);
                if (result != null) return result;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeAt(float normX, float normY) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        int x = (int) (normX * screenWidth);
        int y = (int) (normY * screenHeight);
        return findClickableAt(root, x, y);
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return findEditableRecursive(root);
    }

    private AccessibilityNodeInfo findEditableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) return node;
        if (node.isEditable() && node.isSelected()) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findEditableRecursive(child);
                if (result != null) return result;
            }
        }
        return null;
    }
}
