package com.projector.remote;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量级 WebSocket 服务器，零外部依赖。
 * 负责与手机浏览器建立 WebSocket 连接，接收触控/键盘事件。
 */
public class WebSocketServer {

    private static final String TAG = "WSServer";
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-5AB9D111CF85";

    public interface MessageCallback {
        void onMessage(String message);
        void onClientConnected(String remoteAddress);
        void onClientDisconnected(String remoteAddress);
    }

    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final Set<ClientHandler> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private MessageCallback callback;

    public WebSocketServer(int port) {
        this.port = port;
    }

    public void setCallback(MessageCallback callback) {
        this.callback = callback;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        Log.i(TAG, "WebSocket server started on port " + port);

        new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                    executor.submit(new ClientHandler(client));
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        }, "ws-accept").start();
    }

    public void stop() {
        running = false;
        for (ClientHandler client : clients) {
            client.close();
        }
        clients.clear();
        executor.shutdownNow();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * 向所有连接的客户端广播消息
     */
    public void broadcast(String message) {
        byte[] payload = message.getBytes();
        byte[] frame = encodeFrame(payload);
        for (ClientHandler client : clients) {
            client.send(frame);
        }
    }

    private byte[] encodeFrame(byte[] payload) {
        int len = payload.length;
        byte[] frame;
        if (len <= 125) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x81; // FIN + TEXT
            frame[1] = (byte) len;
            System.arraycopy(payload, 0, frame, 2, len);
        } else if (len <= 65535) {
            frame = new byte[4 + len];
            frame[0] = (byte) 0x81;
            frame[1] = 126;
            frame[2] = (byte) (len >> 8);
            frame[3] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, frame, 4, len);
        } else {
            frame = new byte[10 + len];
            frame[0] = (byte) 0x81;
            frame[1] = 127;
            for (int i = 0; i < 8; i++) {
                frame[9 - i] = (byte) (len & 0xFF);
                len >>= 8;
            }
            System.arraycopy(payload, 0, frame, 10, len);
        }
        return frame;
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private volatile boolean connected = true;
        private OutputStream out;
        private String remoteAddress;

        ClientHandler(Socket socket) {
            this.socket = socket;
            this.remoteAddress = socket.getRemoteSocketAddress().toString();
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();

                // 读取 HTTP 握手请求
                String request = readHttpRequest(in);
                if (!request.contains("Upgrade: websocket") && !request.contains("Upgrade: WebSocket")) {
                    socket.close();
                    return;
                }

                // 提取 Sec-WebSocket-Key
                String key = extractHeader(request, "Sec-WebSocket-Key");
                if (key == null) {
                    socket.close();
                    return;
                }

                // 发送握手响应
                String accept = sha1Base64(key.trim() + WS_MAGIC);
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(response.getBytes());
                out.flush();

                clients.add(this);
                Log.i(TAG, "Client connected: " + remoteAddress);
                if (callback != null) callback.onClientConnected(remoteAddress);

                // 读取消息循环
                while (connected && running) {
                    String message = readFrame(in);
                    if (message == null) break;
                    if (callback != null) callback.onMessage(message);
                }

            } catch (Exception e) {
                if (connected) Log.d(TAG, "Client error: " + e.getMessage());
            } finally {
                close();
            }
        }

        synchronized void send(byte[] frame) {
            try {
                if (connected && out != null) {
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (!connected) return;
            connected = false;
            clients.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
            Log.i(TAG, "Client disconnected: " + remoteAddress);
            if (callback != null) callback.onClientDisconnected(remoteAddress);
        }

        private String readHttpRequest(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int prev = 0, cur;
            while ((cur = in.read()) != -1) {
                sb.append((char) cur);
                if (prev == '\r' && cur == '\n') {
                    // 检查是否到达请求末尾 (\r\n\r\n)
                    String s = sb.toString();
                    if (s.endsWith("\r\n\r\n")) break;
                }
                prev = cur;
            }
            return sb.toString();
        }

        private String extractHeader(String request, String headerName) {
            for (String line : request.split("\r\n")) {
                if (line.startsWith(headerName + ":")) {
                    return line.substring(headerName.length() + 1).trim();
                }
            }
            return null;
        }

        private String readFrame(InputStream in) throws IOException {
            int b1 = in.read();
            if (b1 == -1) return null;
            int b2 = in.read();
            if (b2 == -1) return null;

            int opcode = b1 & 0x0F;
            // 关闭帧
            if (opcode == 0x8) return null;
            // Ping -> Pong
            if (opcode == 0x9) {
                byte[] pingPayload = readRawPayload(in, b2);
                byte[] pong = new byte[2 + pingPayload.length];
                pong[0] = (byte) 0x8A;
                pong[1] = (byte) pingPayload.length;
                System.arraycopy(pingPayload, 0, pong, 2, pingPayload.length);
                send(pong);
                return readFrame(in);
            }

            boolean masked = (b2 & 0x80) != 0;
            long payloadLen = b2 & 0x7F;

            if (payloadLen == 126) {
                int h = in.read(), l = in.read();
                if (h == -1 || l == -1) return null;
                payloadLen = (h << 8) | l;
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) {
                    int rb = in.read();
                    if (rb == -1) return null;
                    payloadLen = (payloadLen << 8) | rb;
                }
            }

            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(in, maskKey);
            }

            byte[] payload = new byte[(int) payloadLen];
            readFully(in, payload);

            if (masked && maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }

            return new String(payload, "UTF-8");
        }

        private byte[] readRawPayload(InputStream in, int b2) throws IOException {
            boolean masked = (b2 & 0x80) != 0;
            int len = b2 & 0x7F;
            if (len == 126) {
                int h = in.read(), l = in.read();
                len = (h << 8) | l;
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            byte[] mask = null;
            if (masked) { mask = new byte[4]; readFully(in, mask); }
            byte[] payload = new byte[len];
            readFully(in, payload);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }
            return payload;
        }

        private void readFully(InputStream in, byte[] buf) throws IOException {
            int offset = 0;
            while (offset < buf.length) {
                int n = in.read(buf, offset, buf.length - offset);
                if (n == -1) throw new IOException("EOF");
                offset += n;
            }
        }

        private String sha1Base64(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] sha = md.digest(input.getBytes("UTF-8"));
                return android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
