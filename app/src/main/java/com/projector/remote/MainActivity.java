package com.projector.remote;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8081;

    private HttpServer httpServer;
    private WebSocketServer wsServer;
    private EventRouter eventRouter;

    private TextView tvStatus;
    private TextView tvAddress;
    private TextView tvPort;
    private TextView tvClientCount;
    private ImageView ivQr;
    private Button btnToggle;
    private LinearLayout llQrContainer;

    private boolean serverRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateClientsRunnable = this::updateClientCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏 + 常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvAddress = findViewById(R.id.tv_address);
        tvPort = findViewById(R.id.tv_port);
        tvClientCount = findViewById(R.id.tv_client_count);
        ivQr = findViewById(R.id.iv_qr);
        btnToggle = findViewById(R.id.btn_toggle);
        llQrContainer = findViewById(R.id.ll_qr_container);

        btnToggle.setOnClickListener(v -> {
            if (serverRunning) {
                stopServers();
            } else {
                startServers();
            }
        });

        // 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
        }

        // 自动启动
        startServers();
    }

    @Override
    protected void onDestroy() {
        stopServers();
        handler.removeCallbacks(updateClientsRunnable);
        super.onDestroy();
    }

    private void startServers() {
        String ip = NetworkUtils.getLocalIpAddress(this);

        try {
            httpServer = new HttpServer(this, HTTP_PORT);
            httpServer.start();
            Log.i(TAG, "HTTP server started on port " + HTTP_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            Toast.makeText(this, "HTTP 服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            wsServer = new WebSocketServer(WS_PORT);
            eventRouter = new EventRouter();
            eventRouter.setWsServer(wsServer);
            wsServer.setCallback(eventRouter);
            wsServer.start();
            Log.i(TAG, "WebSocket server started on port " + WS_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start WebSocket server", e);
            Toast.makeText(this, "WebSocket 服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            httpServer.stop();
            return;
        }

        serverRunning = true;
        String url = "http://" + ip + ":" + HTTP_PORT;

        // 更新 UI
        tvStatus.setText("● 服务运行中");
        tvStatus.setTextColor(Color.parseColor("#4caf50"));
        tvAddress.setText(ip);
        tvPort.setText(String.valueOf(HTTP_PORT));
        btnToggle.setText("停止服务");

        // 生成二维码
        generateQrCode(url);

        // 定时更新客户端数量
        handler.postDelayed(updateClientsRunnable, 2000);

        Toast.makeText(this, "服务已启动: " + url, Toast.LENGTH_SHORT).show();
    }

    private void stopServers() {
        if (wsServer != null) {
            wsServer.stop();
            wsServer = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }

        serverRunning = false;
        handler.removeCallbacks(updateClientsRunnable);

        tvStatus.setText("○ 服务已停止");
        tvStatus.setTextColor(Color.parseColor("#f44336"));
        tvAddress.setText("-");
        tvPort.setText("-");
        tvClientCount.setText("已连接: 0");
        btnToggle.setText("启动服务");
        ivQr.setImageBitmap(null);
    }

    private void updateClientCount() {
        if (wsServer != null) {
            tvClientCount.setText("已连接: " + wsServer.getClientCount());
        }
        if (serverRunning) {
            handler.postDelayed(updateClientsRunnable, 2000);
        }
    }

    private void generateQrCode(String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 400, 400);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = matrix.get(x, y) ? Color.WHITE : Color.TRANSPARENT;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            ivQr.setImageBitmap(bmp);
            llQrContainer.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "QR generation failed", e);
            llQrContainer.setVisibility(View.GONE);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + InputAccessibilityService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            return enabledServices.contains(serviceName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 打开无障碍设置页面（布局中可绑定此方法）
     */
    public void openAccessibilitySettings(View view) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
}
