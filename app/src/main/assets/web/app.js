/**
 * 投影遥控 - 手机触控板客户端
 *
 * 核心设计：
 * 1. 事件批处理：将高频 touchmove 事件攒到一帧内发送，减少 WebSocket 帧数
 * 2. 二进制帧：WebSocket 帧开销最小化
 * 3. 自动重连：断线后指数退避重连
 * 4. 低延迟模式：禁用 Nagle（浏览器端无法直接控制，但 WebSocket 默认就是）
 */

(function() {
  'use strict';

  // ==================== 配置 ====================
  const CONFIG = {
    // 触控灵敏度（像素移动倍率）
    sensitivity: 1.5,
    // 最小移动阈值（像素），低于此值不发送
    moveThreshold: 1,
    // 事件发送间隔（ms），16 ≈ 60fps
    sendInterval: 8,
    // 心跳间隔（ms）
    heartbeatInterval: 5000,
    // 重连参数
    reconnectBaseDelay: 500,
    reconnectMaxDelay: 5000,
    // 长按判定时间（ms）
    longPressTime: 500,
    // 双击判定间隔（ms）
    doubleClickTime: 300,
    // 拖拽判定距离阈值（像素）
    dragThreshold: 10,
    // 滚动灵敏度
    scrollSensitivity: 0.5,
  };

  // ==================== 状态 ====================
  let ws = null;
  let wsUrl = '';
  let reconnectDelay = CONFIG.reconnectBaseDelay;
  let reconnectTimer = null;
  let connected = false;

  // 触控状态
  let touching = false;
  let touchId = null;
  let lastTouchX = 0;
  let lastTouchY = 0;
  let touchStartX = 0;
  let touchStartY = 0;
  let touchStartTime = 0;
  let longPressTimer = null;
  let isDragging = false;
  let lastClickTime = 0;
  let clickTimer = null;

  // 当前模式：'cursor' | 'scroll'
  let mode = 'scroll';  // 默认滚动模式，更常用

  // 批处理队列
  let pendingMoves = { dx: 0, dy: 0 };
  let sendTimer = null;
  let batchDirty = false;

  // 延迟测量
  let pingTime = 0;
  let latency = 0;

  // DOM 元素
  const touchpad = document.getElementById('touchpad');
  const cursorIndicator = document.getElementById('cursor-indicator');
  const statusDot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');
  const latencyDisplay = document.getElementById('latency-display');
  const keyboardPanel = document.getElementById('keyboard-panel');
  const keyboardInput = document.getElementById('keyboard-input');

  // ==================== WebSocket 连接 ====================

  function connect() {
    // 自动检测 WebSocket 地址（与 HTTP 同源）
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    // WebSocket 端口 = HTTP 端口 + 1
    const wsPort = parseInt(location.port || '80') + 1;
    wsUrl = `${proto}//${location.hostname}:${wsPort}`;

    updateStatus('connecting', '连接中...');

    try {
      ws = new WebSocket(wsUrl);
    } catch (e) {
      updateStatus('error', '连接失败');
      scheduleReconnect();
      return;
    }

    ws.onopen = function() {
      connected = true;
      reconnectDelay = CONFIG.reconnectBaseDelay;
      updateStatus('connected', '已连接');
      startHeartbeat();
    };

    ws.onmessage = function(evt) {
      // 处理 pong 响应
      try {
        const msg = JSON.parse(evt.data);
        if (msg.t === 'pong') {
          latency = Date.now() - pingTime;
          latencyDisplay.textContent = latency + 'ms';
          latencyDisplay.style.color = latency < 30 ? '#4caf50' : latency < 60 ? '#ff9800' : '#f44336';
        }
      } catch (e) {}
    };

    ws.onclose = function() {
      connected = false;
      updateStatus('disconnected', '已断开');
      stopHeartbeat();
      scheduleReconnect();
    };

    ws.onerror = function() {
      updateStatus('error', '连接错误');
    };
  }

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj));
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function() {
      reconnectDelay = Math.min(reconnectDelay * 1.5, CONFIG.reconnectMaxDelay);
      connect();
    }, reconnectDelay);
  }

  // ==================== 心跳 ====================

  let heartbeatTimer = null;

  function startHeartbeat() {
    stopHeartbeat();
    pingTime = Date.now();
    send({ t: 'p' });
    heartbeatTimer = setInterval(function() {
      pingTime = Date.now();
      send({ t: 'p' });
    }, CONFIG.heartbeatInterval);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  // ==================== 状态 UI ====================

  function updateStatus(state, text) {
    statusText.textContent = text;
    statusDot.className = state === 'connected' ? 'connected' : '';
  }

  // ==================== 触控板事件处理 ====================

  // 鼠标移动批处理
  function flushMoves() {
    if (!batchDirty) return;
    const dx = Math.round(pendingMoves.dx);
    const dy = Math.round(pendingMoves.dy);
    if (dx !== 0 || dy !== 0) {
      send({ t: 'm', dx: dx, dy: dy });
    }
    pendingMoves.dx = 0;
    pendingMoves.dy = 0;
    batchDirty = false;
  }

  function startBatchFlusher() {
    if (sendTimer) return;
    sendTimer = setInterval(flushMoves, CONFIG.sendInterval);
  }

  function stopBatchFlusher() {
    if (sendTimer) {
      clearInterval(sendTimer);
      sendTimer = null;
    }
    flushMoves();
  }

  // ---- Pointer Events (推荐，支持鼠标+触摸) ----

  let pointerDown = false;
  let pointerId = null;
  let pointerStartX = 0;
  let pointerStartY = 0;
  let pointerStartTime = 0;
  let pointerMoved = false;
  let pointerLongPressTimer = null;

  touchpad.addEventListener('pointerdown', function(e) {
    if (pointerDown) return; // 已有指针按下
    pointerDown = true;
    pointerId = e.pointerId;
    pointerStartX = e.clientX;
    pointerStartY = e.clientY;
    lastTouchX = e.clientX;
    lastTouchY = e.clientY;
    pointerStartTime = Date.now();
    pointerMoved = false;

    touchpad.setPointerCapture(e.pointerId);
    e.preventDefault();

    // 长按检测
    pointerLongPressTimer = setTimeout(function() {
      if (pointerDown && !pointerMoved) {
        send({ t: 'lc' });
        addRipple(pointerStartX, pointerStartY);
        // 长按后不触发后续点击
        pointerDown = false;
        pointerMoved = true;
      }
    }, CONFIG.longPressTime);

    // 拖拽模式下开始拖拽
    if (mode === 'cursor') {
      send({ t: 'dss', x: e.clientX / touchpad.offsetWidth, y: e.clientY / touchpad.offsetHeight });
      touchpad.classList.add('dragging');
    }
  });

  touchpad.addEventListener('pointermove', function(e) {
    if (!pointerDown || e.pointerId !== pointerId) return;

    const dx = e.clientX - lastTouchX;
    const dy = e.clientY - lastTouchY;

    // 判断是否移动超过阈值
    const totalDx = e.clientX - pointerStartX;
    const totalDy = e.clientY - pointerStartY;
    if (Math.abs(totalDx) > CONFIG.dragThreshold || Math.abs(totalDy) > CONFIG.dragThreshold) {
      if (!pointerMoved) {
        pointerMoved = true;
        if (pointerLongPressTimer) {
          clearTimeout(pointerLongPressTimer);
          pointerLongPressTimer = null;
        }
      }
    }

    if (mode === 'scroll') {
      // 滚动模式：直接发送滚动事件
      if (Math.abs(dy) > 2) {
        send({ t: 's', dy: Math.round(dy * CONFIG.scrollSensitivity) });
      }
    } else {
      // 鼠标模式：累积移动量
      const absDx = Math.abs(dx);
      const absDy = Math.abs(dy);
      if (absDx >= CONFIG.moveThreshold || absDy >= CONFIG.moveThreshold) {
        pendingMoves.dx += dx * CONFIG.sensitivity;
        pendingMoves.dy += dy * CONFIG.sensitivity;
        batchDirty = true;
      }
    }

    lastTouchX = e.clientX;
    lastTouchY = e.clientY;
    e.preventDefault();
  });

  touchpad.addEventListener('pointerup', function(e) {
    if (e.pointerId !== pointerId) return;
    pointerDown = false;
    touchpad.releasePointerCapture(e.pointerId);

    if (pointerLongPressTimer) {
      clearTimeout(pointerLongPressTimer);
      pointerLongPressTimer = null;
    }

    touchpad.classList.remove('dragging');

    // 如果没有移动，视为点击
    if (!pointerMoved && mode === 'cursor') {
      const now = Date.now();
      const timeSinceLastClick = now - lastClickTime;

      if (timeSinceLastClick < CONFIG.doubleClickTime) {
        // 双击
        if (clickTimer) {
          clearTimeout(clickTimer);
          clickTimer = null;
        }
        send({ t: 'dc' });
        lastClickTime = 0;
      } else {
        // 单击（延迟发送，等待可能的双击）
        lastClickTime = now;
        clickTimer = setTimeout(function() {
          send({ t: 'c' });
          clickTimer = null;
        }, CONFIG.doubleClickTime);
      }
      addRipple(pointerStartX, pointerStartY);
    }

    if (mode === 'cursor' && pointerMoved) {
      send({ t: 'de' });
    }
  });

  touchpad.addEventListener('pointercancel', function(e) {
    if (e.pointerId !== pointerId) return;
    pointerDown = false;
    touchpad.classList.remove('dragging');
    if (pointerLongPressTimer) {
      clearTimeout(pointerLongPressTimer);
      pointerLongPressTimer = null;
    }
  });

  // 阻止默认的触摸行为（防止页面滚动/缩放）
  touchpad.addEventListener('touchstart', function(e) { e.preventDefault(); }, { passive: false });
  touchpad.addEventListener('touchmove', function(e) { e.preventDefault(); }, { passive: false });

  // ==================== 视觉反馈 ====================

  function addRipple(x, y) {
    const rect = touchpad.getBoundingClientRect();
    const ripple = document.createElement('div');
    ripple.className = 'touch-ripple';
    ripple.style.left = (x - rect.left) + 'px';
    ripple.style.top = (y - rect.top) + 'px';
    touchpad.appendChild(ripple);
    setTimeout(function() { ripple.remove(); }, 400);
  }

  // ==================== 底部按钮 ====================

  // 模式切换
  document.getElementById('btn-scroll').addEventListener('click', function() {
    mode = 'scroll';
    this.classList.add('active-mode');
    document.getElementById('btn-keyboard').classList.remove('active-mode');
    touchpad.classList.remove('dragging');
  });

  document.getElementById('btn-keyboard').addEventListener('click', function() {
    keyboardPanel.classList.toggle('hidden');
    this.classList.toggle('active-mode');
    if (!keyboardPanel.classList.contains('hidden')) {
      keyboardInput.focus();
    }
  });

  // 全局动作按钮
  ['btn-back', 'btn-home', 'btn-recents'].forEach(function(id) {
    const btn = document.getElementById(id);
    btn.addEventListener('click', function() {
      const action = this.dataset.action;
      send({ t: 'a', action: action });
      // 视觉反馈
      this.style.color = '#4fc3f7';
      setTimeout(() => { this.style.color = ''; }, 200);
    });
  });

  // ==================== 虚拟键盘 ====================

  document.getElementById('btn-kb-close').addEventListener('click', function() {
    keyboardPanel.classList.add('hidden');
    document.getElementById('btn-keyboard').classList.remove('active-mode');
  });

  // 输入框：实时发送字符
  let inputBuffer = '';
  keyboardInput.addEventListener('input', function() {
    const val = this.value;
    if (val.length > inputBuffer.length) {
      // 新增字符
      const newChars = val.substring(inputBuffer.length);
      send({ t: 't', text: newChars });
    } else if (val.length < inputBuffer.length) {
      // 删除字符
      send({ t: 'bs' });
    }
    inputBuffer = val;
  });

  // 发送按钮
  document.getElementById('btn-kb-send').addEventListener('click', function() {
    if (keyboardInput.value) {
      send({ t: 't', text: keyboardInput.value });
      keyboardInput.value = '';
      inputBuffer = '';
    }
  });

  // 退格按钮
  document.getElementById('btn-kb-bs').addEventListener('click', function() {
    send({ t: 'bs' });
    if (keyboardInput.value) {
      keyboardInput.value = keyboardInput.value.slice(0, -1);
      inputBuffer = keyboardInput.value;
    }
  });

  // 虚拟键盘按键
  let shiftActive = false;

  document.querySelectorAll('.kb-key').forEach(function(key) {
    key.addEventListener('click', function(e) {
      e.stopPropagation();
      let text = this.textContent;

      // 特殊键处理
      if (text === '⌫') {
        send({ t: 'bs' });
        return;
      }
      if (text === '回车') {
        send({ t: 'k', key: 'enter' });
        return;
      }
      if (text === '空格') {
        send({ t: 't', text: ' ' });
        return;
      }
      if (text === '⇧') {
        shiftActive = !shiftActive;
        this.classList.toggle('active-mode', shiftActive);
        // 切换所有字母键大小写
        document.querySelectorAll('.kb-key').forEach(function(k) {
          if (k.textContent.length === 1 && /[a-z]/i.test(k.textContent)) {
            k.textContent = shiftActive ? k.textContent.toUpperCase() : k.textContent.toLowerCase();
          }
        });
        return;
      }

      // 普通字符
      if (shiftActive) {
        text = text.toUpperCase();
        shiftActive = false;
        document.querySelector('.kb-key.wide').classList.remove('active-mode');
        document.querySelectorAll('.kb-key').forEach(function(k) {
          if (k.textContent.length === 1 && /[A-Z]/.test(k.textContent)) {
            k.textContent = k.textContent.toLowerCase();
          }
        });
      }

      send({ t: 't', text: text });
    });
  });

  // ==================== 初始化 ====================

  startBatchFlusher();
  connect();

  // 页面可见性变化时重连
  document.addEventListener('visibilitychange', function() {
    if (!document.hidden && !connected) {
      connect();
    }
  });

  // 窗口大小变化时更新（防止缩放问题）
  window.addEventListener('resize', function() {
    document.body.style.height = window.innerHeight + 'px';
  });

  // 调试：显示连接信息
  console.log('[ProjectorRemote] Touchpad ready');

})();
