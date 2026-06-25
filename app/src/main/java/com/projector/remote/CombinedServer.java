package com.projector.remote;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CombinedServer {

    private static final String TAG = "CombinedServer";
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-5AB9D111CF85";
    private static final String SERVER_NAME = "ProjectorRemote/1.0";

    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    static {
        MIME_TYPES.put("html", "text/html; charset=utf-8");
        MIME_TYPES.put("css", "text/css; charset=utf-8");
        MIME_TYPES.put("js", "application/javascript; charset=utf-8");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("json", "application/json; charset=utf-8");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("txt", "text/plain; charset=utf-8");
    }

    public interface MessageCallback {
        void onMessage(String message);
        void onClientConnected(String remoteAddress);
        void onClientDisconnected(String remoteAddress);
    }

    private final int port;
    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final Set<WsClient> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private MessageCallback callback;

    public CombinedServer(Context context, int port) {
        this.context = context;
        this.port = port;
    }

    public void setCallback(MessageCallback callback) {
        this.callback = callback;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        Log.i(TAG, "Combined server started on port " + port);

        new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        }, "server-accept").start();
    }

    public void stop() {
        running = false;
        for (WsClient client : clients) {
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

    public void broadcast(String message) {
        byte[] payload = message.getBytes();
        byte[] frame = encodeFrame(payload);
        for (WsClient client : clients) {
            client.send(frame);
        }
    }

    private void handleClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    headers.put(line.substring(0, colonIdx).trim().toLowerCase(Locale.US),
                            line.substring(colonIdx + 1).trim());
                }
            }

            String upgrade = headers.get("upgrade");
            if ("websocket".equalsIgnoreCase(upgrade)) {
                handleWebSocket(client, in, out, requestLine, headers);
            } else if (requestLine.startsWith("GET")) {
                handleHttp(client, in, out, requestLine, headers);
            } else {
                sendHttpResponse(out, "501 Not Implemented", "text/plain", "Not Implemented".getBytes());
                client.close();
            }
        } catch (Exception e) {
            Log.d(TAG, "Client error: " + e.getMessage());
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // ==================== HTTP ====================

    private void handleHttp(Socket client, InputStream in, OutputStream out,
                            String requestLine, Map<String, String> headers) throws IOException {
        String path = extractPath(requestLine);
        String ip = requestLine.split(" ")[1];

        if ("/api/info".equals(path)) {
            String json = "{\"httpPort\":" + port + ",\"wsPort\":" + port + "}";
            sendHttpResponse(out, "200 OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
            client.close();
            return;
        }

        if ("/api/ping".equals(path)) {
            String json = "{\"status\":\"ok\",\"time\":" + System.currentTimeMillis() + "}";
            sendHttpResponse(out, "200 OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
            client.close();
            return;
        }

        String filePath = path;
        if ("/".equals(path) || path.isEmpty()) {
            filePath = "/web/index.html";
        } else if (path.startsWith("/")) {
            filePath = "/web" + path;
        } else {
            filePath = "/web/" + path;
        }

        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open(filePath.substring(1));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            byte[] data = baos.toByteArray();
            String ext = "";
            int dotIdx = filePath.lastIndexOf('.');
            if (dotIdx > 0) ext = filePath.substring(dotIdx + 1).toLowerCase(Locale.US);
            String mime = MIME_TYPES.getOrDefault(ext, "application/octet-stream");
            sendHttpResponse(out, "200 OK", mime, data);
        } catch (IOException e) {
            sendHttpResponse(out, "404 Not Found", "text/plain", "404 Not Found".getBytes());
        }
        client.close();
    }

    private void sendHttpResponse(OutputStream out, String status, String contentType, byte[] body) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append("\r\n");
        header.append("Server: ").append(SERVER_NAME).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Cache-Control: no-cache\r\n");
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        header.append("Date: ").append(sdf.format(new Date())).append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    // ==================== WebSocket ====================

    private void handleWebSocket(Socket socket, InputStream in, OutputStream out,
                                 String requestLine, Map<String, String> headers) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            sendHttpResponse(out, "400 Bad Request", "text/plain", "Missing Sec-WebSocket-Key".getBytes());
            socket.close();
            return;
        }

        String accept = sha1Base64(key.trim() + WS_MAGIC);
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(response.getBytes());
        out.flush();

        WsClient wsClient = new WsClient(socket, in, out);
        String remoteAddress = socket.getRemoteSocketAddress().toString();

        clients.add(wsClient);
        Log.i(TAG, "WS client connected: " + remoteAddress);
        if (callback != null) callback.onClientConnected(remoteAddress);

        try {
            while (wsClient.connected && running) {
                String message = readFrame(in);
                if (message == null) break;
                if (callback != null) callback.onMessage(message);
            }
        } catch (Exception e) {
            if (wsClient.connected) Log.d(TAG, "WS client error: " + e.getMessage());
        } finally {
            wsClient.close();
            clients.remove(wsClient);
            Log.i(TAG, "WS client disconnected: " + remoteAddress);
            if (callback != null) callback.onClientDisconnected(remoteAddress);
        }
    }

    // ==================== WebSocket Frame ====================

    private static byte[] encodeFrame(byte[] payload) {
        int len = payload.length;
        byte[] frame;
        if (len <= 125) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x81;
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

    private String readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) return null;
        int b2 = in.read();
        if (b2 == -1) return null;

        int opcode = b1 & 0x0F;
        if (opcode == 0x8) return null;
        if (opcode == 0x9) {
            byte[] pingPayload = readRawPayload(in, b2);
            byte[] pong = new byte[2 + pingPayload.length];
            pong[0] = (byte) 0x8A;
            pong[1] = (byte) pingPayload.length;
            System.arraycopy(pingPayload, 0, pong, 2, pingPayload.length);
            if (!clients.isEmpty()) {
                for (WsClient c : clients) {
                    if (c.connected) { c.send(pong); break; }
                }
            }
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

    // ==================== Helpers ====================

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n == -1) throw new IOException("EOF");
            offset += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.mark(1);
                int next = in.read();
                if (next == '\n') break;
                in.reset();
                break;
            }
            if (c == '\n') break;
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String extractPath(String requestLine) {
        if (requestLine == null) return "/";
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return "/";
        String path = parts[1];
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {}
        int qIdx = path.indexOf('?');
        return qIdx > 0 ? path.substring(0, qIdx) : path;
    }

    private static String sha1Base64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] sha = md.digest(input.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== WebSocket Client ====================

    private static class WsClient {
        final Socket socket;
        final OutputStream out;
        volatile boolean connected = true;

        WsClient(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.out = out;
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
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
