# 📽️ 投影遥控 (Projector Remote)

将手机变成投影仪的无线触控鼠标，无需在手机上安装任何 App，浏览器即开即用。

## ✨ 特性

- **零安装**：手机只需打开浏览器，扫码或输入 IP 即可连接
- **超低延迟**：局域网 WebSocket 直连，延迟 < 30ms
- **无 Root 要求**：通过 Android 无障碍服务实现鼠标控制
- **兼容性广**：支持 Android 4.4 ~ 16（API 19-36）
- **功能完整**：鼠标移动、单击、双击、长按、拖拽、滚轮、键盘输入
- **极简体积**：无多余依赖，APK < 500KB

## 📦 架构

```
┌──────────────────────┐        WebSocket (8081)       ┌─────────────────┐
│  投影仪 Android App    │ ◄─────────────────────────── │  手机浏览器       │
│                        │                              │                  │
│  ┌─────────────────┐  │        HTTP (8080)           │  ┌────────────┐  │
│  │ WebSocket Server │  │ ◄──── 加载触控板页面 ──────── │  │ 触控板 UI   │  │
│  │ (事件接收)       │  │                              │  │ 键盘输入    │  │
│  └────────┬────────┘  │                              │  └────────────┘  │
│           │            │                              └─────────────────┘
│  ┌────────▼────────┐  │
│  │ Accessibility    │  │
│  │ Service          │  │
│  │ (鼠标/键盘注入)  │  │
│  └─────────────────┘  │
└──────────────────────┘
```

## 🚀 快速开始

### 1. 构建 APK

```bash
# 在 Android Studio 中打开项目，或使用命令行：
cd projector-remote
./gradlew assembleDebug

# APK 输出位置：
# app/build/outputs/apk/debug/app-debug.apk
```

### 2. 安装到投影仪

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开启无障碍服务

投影仪上打开 App → 点击"无障碍设置" → 找到"投影遥控" → 开启

### 4. 手机连接

1. 确保手机和投影仪在同一局域网
2. 打开 App，屏幕会显示连接地址和二维码
3. 手机扫描二维码或手动输入 `http://<IP>:8080`
4. 开始使用！

## 🎮 操作说明

### 触控板模式

| 手势 | 功能 |
|------|------|
| 单指滑动 | 鼠标移动 |
| 单指点击 | 鼠标左键 |
| 双指点击 | 鼠标右键 |
| 长按 (500ms) | 右键菜单 |
| 双指上下滑动 | 滚轮滚动 |

### 底部按钮

| 按钮 | 功能 |
|------|------|
| ← 返回 | Android 返回键 |
| 🏠 主页 | Android Home 键 |
| 📜 滚动 | 切换到滚动模式（默认） |
| ⌨️ 键盘 | 打开虚拟键盘 |
| □ 最近 | 最近任务 |

### 虚拟键盘

- 支持全键盘输入（字母、数字、符号）
- Shift 大小写切换
- 退格、回车、空格
- 实时输入模式：打字即发送

## ⚙️ 技术细节

### 延迟优化

1. **事件批处理**：高频 touchmove 事件攒到一帧内（8ms 间隔）合并发送
2. **移动阈值**：忽略 < 1px 的微小移动，减少无效事件
3. **TCP No Delay**：WebSocket 连接禁用 Nagle 算法
4. **轻量协议**：JSON 消息体 < 50 字节

### 鼠标注入方案

| API 等级 | 方案 | 能力 |
|---------|------|------|
| API 24+ (Android 7.0+) | `dispatchGesture()` | 完整鼠标操作：移动、点击、长按、拖拽、滚轮 |
| API 19-23 (Android 4.4-6.x) | 无障碍节点操作 | 全局动作(返回/Home/最近)、节点点击、文本输入 |

### 通信协议

WebSocket JSON 格式：

```json
// 鼠标移动（增量）
{"t":"m","dx":5,"dy":-3}

// 点击
{"t":"c"}       // 单击
{"t":"dc"}      // 双击
{"t":"lc"}      // 长按

// 滚轮
{"t":"s","dy":1}    // dy>0 向下

// 文本输入
{"t":"t","text":"hello"}

// 按键
{"t":"k","key":"enter"}
{"t":"k","key":"backspace"}

// 全局动作
{"t":"a","action":"back"}
{"t":"a","action":"home"}
{"t":"a","action":"recents"}

// 心跳
{"t":"p"}       // 客户端 → 服务端
{"t":"pong"}    // 服务端 → 客户端
```

## 📁 项目结构

```
projector-remote/
├── app/
│   └── src/main/
│       ├── java/com/projector/remote/
│       │   ├── MainActivity.java          # 主界面（启动服务+显示连接信息）
│       │   ├── HttpServer.java            # HTTP 服务器（托管 Web 页面）
│       │   ├── WebSocketServer.java       # WebSocket 服务器（事件传输）
│       │   ├── EventRouter.java           # 事件路由（解析消息→调用无障碍服务）
│       │   ├── InputAccessibilityService.java  # 无障碍服务（鼠标/键盘注入核心）
│       │   └── NetworkUtils.java          # 网络工具（获取 IP 地址）
│       ├── assets/web/
│       │   ├── index.html                 # 触控板页面
│       │   ├── style.css                  # 样式
│       │   └── app.js                     # 触控板逻辑
│       └── AndroidManifest.xml
├── build.gradle
└── README.md
```

## 🔧 自定义配置

在 `app.js` 顶部可调整：

```javascript
const CONFIG = {
  sensitivity: 1.5,       // 触控灵敏度（1.0-3.0）
  moveThreshold: 1,       // 移动阈值（像素）
  sendInterval: 8,        // 发送间隔（ms）
  longPressTime: 500,     // 长按判定时间（ms）
  doubleClickTime: 300,   // 双击判定间隔（ms）
  scrollSensitivity: 0.5, // 滚动灵敏度
};
```

## ⚠️ 注意事项

1. **无障碍服务必须手动开启**：首次使用需在系统设置中开启
2. **Android 4.4-6.x 功能受限**：这些版本不支持 `dispatchGesture()`，鼠标移动和滚轮功能有限
3. **WiFi 网络**：确保手机和投影仪在同一局域网（同一路由器下）
4. **防火墙**：如果连接不上，检查投影仪是否放行了 8080/8081 端口
5. **部分定制系统**：极米、坚果等品牌可能对无障碍服务有限制

## 📄 License

MIT
