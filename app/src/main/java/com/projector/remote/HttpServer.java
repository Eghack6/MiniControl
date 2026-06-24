package com.projector.remote;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP 服务器，负责托管 Web 触控板页面。
 * 手机浏览器访问 http://<投影仪IP>:<端口>/ 即可加载页面。
 */
public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";
    private final Context context;

    // MIME 类型映射
    private static final String MIME_HTML = "text/html; charset=utf-8";
    private static final String MIME_CSS = "text/css; charset=utf-8";
    private static final String MIME_JS = "application/javascript; charset=utf-8";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_ICO = "image/x-icon";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    public HttpServer(Context context, int port) {
        super(port);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        // API: 返回服务器信息
        if ("/api/info".equals(uri)) {
            String json = "{\"httpPort\":" + getListeningPort() + ",\"wsPort\":" + (getListeningPort() + 1) + "}";
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json);
        }

        // 静态文件服务
        if ("/".equals(uri) || "".equals(uri)) {
            uri = "/web/index.html";
        } else if (uri.startsWith("/")) {
            uri = "/web" + uri;
        }

        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open(uri.substring(1)); // 去掉开头的 /
            String mime = getMimeType(uri);
            return newChunkedResponse(Response.Status.OK, mime, is);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
        }
    }

    private String getMimeType(String uri) {
        if (uri.endsWith(".html")) return MIME_HTML;
        if (uri.endsWith(".css")) return MIME_CSS;
        if (uri.endsWith(".js")) return MIME_JS;
        if (uri.endsWith(".png")) return MIME_PNG;
        if (uri.endsWith(".ico")) return MIME_ICO;
        if (uri.endsWith(".json")) return MIME_JSON;
        return "application/octet-stream";
    }
}
