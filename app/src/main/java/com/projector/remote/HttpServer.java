package com.projector.remote;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";
    private final Context context;

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

    public void setEventRouter(EventRouter router) {
        this.eventRouter = router;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if ("/api/info".equals(uri)) {
            String json = "{\"httpPort\":" + getListeningPort() + ",\"wsPort\":" + (getListeningPort() + 1) + "}";
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json);
        }

        if ("/api/ping".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{\"status\":\"ok\"}");
        }

        if ("/api/event".equals(uri)) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String data = body.get("data");
                if (data != null && !data.isEmpty()) {
                    EventRouter router = new EventRouter();
                    router.processMessage(data);
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{\"ok\":true}");
            } catch (Exception e) {
                Log.w(TAG, "Event error: " + e.getMessage());
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, "{\"ok\":false}");
            }
        }

        if ("/".equals(uri) || "".equals(uri)) {
            uri = "/web/index.html";
        } else if (uri.startsWith("/")) {
            uri = "/web" + uri;
        }

        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open(uri.substring(1));
            String mime = getMimeType(uri);
            Response resp = newChunkedResponse(Response.Status.OK, mime, is);
            resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            resp.addHeader("Access-Control-Allow-Origin", "*");
            return resp;
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
