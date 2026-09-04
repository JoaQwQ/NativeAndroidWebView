package com.sgmn.nativewebview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.PermissionRequest;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class WebViewActivity extends android.app.Activity {

    private static final String TAG = "WebViewActivity";
    private static final int REQ_MIC_PERMISSION = 1001;

    // 原生 WebView 默认 UA 含 "; wv;"，部分网页（豆包等）会据此判定为嵌入式 WebView
    // 并禁用语音能力，连接请求根本不发。伪装成移动版 Chrome 绕开该检测。
    // 与 WebViewMicEnabler.cs 里 Vuplex 版用的是同一个思路。
    private static final String MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView webView;

    // 运行时权限结果回来后再 grant，故暂存。grant/deny 只能调用一次。
    private PermissionRequest pendingPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 不依赖 R 资源：Unity 源码插件会把本类编译进 unityLibrary，
        // 资源 R 的包名（com.unity3d.player）和本类包名（com.sgmn.nativewebview）对不上，
        // 直接写 R.layout / R.id 会“找不到符号”。这里用 Java 代码建全屏 FrameLayout + WebView，
        // 彻底避开 R 依赖，模块 / 扁平两种编译方式都能过。
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        // 顶部悬浮按钮（仿图中样式）
        // 左上角：后退 / 前进 共用一个圆角胶囊；刷新单独一个圆钮；右上角关闭一个圆钮
        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setOrientation(LinearLayout.HORIZONTAL);
        leftControls.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        leftLp.gravity = Gravity.TOP | Gravity.LEFT;
        leftLp.leftMargin = dpToPx(16);
        leftLp.topMargin = dpToPx(16);
        leftControls.setLayoutParams(leftLp);

        // 后退 + 前进：各自带独立圆外框，再装在同一个淡胶囊里（如图：每个箭头单独圈）
        // 用 Canvas 画的三角形替代 Unicode 箭头：字形本身边界不对称，TextView 很难真正居中
        View backBtn = makeArrowButton(0, v -> { if (webView.canGoBack()) webView.goBack(); }, 0xCC333333);
        View forwardBtn = makeArrowButton(1, v -> { if (webView.canGoForward()) webView.goForward(); }, 0xCC333333);
        View capsule = makeCapsule(backBtn, forwardBtn);
        leftControls.addView(capsule);

        // 刷新：独立圆钮（放大到 52dp），与胶囊保持一点间距
        View refreshBtn = makeRefreshButton(v -> webView.reload(), 0xCC333333);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dpToPx(52), dpToPx(52));
        refreshLp.leftMargin = dpToPx(8);
        refreshBtn.setLayoutParams(refreshLp);
        leftControls.addView(refreshBtn);

        root.addView(leftControls);

        // 右上角：关闭（×），单独半透明圆钮（放大到 52dp），颜色与左侧一致
        TextView closeBtn = makeCircleButton("✕", v -> finish(), 0xCC333333);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dpToPx(52), dpToPx(52));
        closeLp.gravity = Gravity.TOP | Gravity.RIGHT;
        closeLp.rightMargin = dpToPx(16);
        closeLp.topMargin = dpToPx(20); // 胶囊高60，关闭52，top=(60-52)/2+16=20 使垂直居中对齐胶囊
        closeBtn.setLayoutParams(closeLp);
        root.addView(closeBtn);

        setContentView(root);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);       // 开启 JS（不加网页里 JS 不跑）
        ws.setDomStorageEnabled(true);        // 开启 DOM Storage，很多 H5 依赖 localStorage
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);         // 支持 <meta viewport>
        ws.setBuiltInZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);  // 允许网页不经用户手势启动音频采集
        ws.setUserAgentString(MOBILE_CHROME_UA);        // 必须早于 loadUrl 设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 仅当 https 页面中混有 http 资源时才起作用
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // 自己处理跳转，避免页面跳转到系统浏览器
        // 内网自签证书：放行 SSL 错误，否则 https 页面直接加载失败（net::ERR_CERT_AUTHORITY_INVALID）。
        // 注意：对外正式发布请删除 onReceivedSslError 整段，改做正常证书校验。
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });
        // 处理 alert / console / 文件选择等
        // 注意：默认 WebChromeClient 的 onPermissionRequest 是空实现（等同拒绝），
        // 网页 getUserMedia 拿不到麦克风，必须覆写并 grant。
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                Log.d(TAG, "onPermissionRequest origin=" + request.getOrigin()
                        + " resources=" + java.util.Arrays.toString(request.getResources()));
                // 若日志从未出现，说明页面不是安全上下文（http 源），需先解决 https。

                // 按网页实际申请的资源，决定要哪些 Android 系统权限
                List<String> need = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            && !need.contains(Manifest.permission.RECORD_AUDIO)) {
                        need.add(Manifest.permission.RECORD_AUDIO);
                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                            && !need.contains(Manifest.permission.CAMERA)) {
                        need.add(Manifest.permission.CAMERA);
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !need.isEmpty()) {
                    // 只申请当前尚未授予的权限，已授予的不再重复弹框
                    List<String> missing = new ArrayList<>();
                    for (String p : need) {
                        if (WebViewActivity.this.checkSelfPermission(p)
                                != PackageManager.PERMISSION_GRANTED) {
                            missing.add(p);
                        }
                    }
                    if (!missing.isEmpty()) {
                        // 麦克风/相机一起申请（同一 requestCode），权限回调里统一判定
                        pendingPermissionRequest = request;
                        WebViewActivity.this.requestPermissions(
                                missing.toArray(new String[0]), REQ_MIC_PERMISSION);
                        return;
                    }
                }
                grantMediaRequest(request);
            }
        });

        // 注入 JS 桥：网页里可用 window.AndroidBridge.xxx() 调原生
        webView.addJavascriptInterface(new JSBridge(), "AndroidBridge");

        // URL 优先取 Intent 传入，否则加载兜底地址（仅开发用，真正使用请由 C# OpenUrl 传入）
        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            url = "https://example.com";
        }
        webView.loadUrl(url);
    }

    // 生成一个圆形图标按钮（仿图中样式）
    // bgColor: 圆钮底色，传 0x00000000 表示透明（无圆外框）
    // 用 TextView 不用 Button：Button 默认有 minWidth/minHeight/padding，很难把图标真正居中
    private TextView makeCircleButton(String icon, View.OnClickListener onClick) {
        return makeCircleButton(icon, onClick, 0xCC333333);
    }

    private TextView makeCircleButton(String icon, View.OnClickListener onClick, int bgColor) {
        TextView btn = new TextView(this);
        btn.setText(icon);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(24);            // 图标大小（随 52dp 圆钮放大）
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, 0, 0, 0);
        btn.setIncludeFontPadding(false);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);

        int size = dpToPx(52);          // 圆钮直径（放大）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dpToPx(6);     // 圆形按钮之间间距（成组但各自独立）
        btn.setLayoutParams(lp);

        if (bgColor != 0x00000000) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(bgColor);
            btn.setBackground(bg);
        }

        btn.setOnClickListener(onClick);
        return btn;
    }

    // 把两个圆钮装在一个半透明胶囊里（前进/后退成组效果）
    // 用 FrameLayout 绝对定位：两个圆钮居中放在更大的胶囊里，四周留大余地（参考图样式）
    private View makeCapsule(View leftBtn, View rightBtn) {
        int size = dpToPx(44);   // 圆钮直径
        int pad  = dpToPx(8);    // 胶囊四周留白（余地）
        int gap  = dpToPx(10);   // 两个圆钮之间的间距

        int capsuleW = pad * 2 + size * 2 + gap; // 114dp
        int capsuleH = pad * 2 + size;           // 60dp

        FrameLayout capsule = new FrameLayout(this);
        capsule.setLayoutParams(new LinearLayout.LayoutParams(capsuleW, capsuleH));

        // 淡色胶囊外壳，圆角=半高 → 胶囊形
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99333333);
        bg.setCornerRadius(capsuleH / 2);
        capsule.setBackground(bg);

        // 左圆：x=pad, y=pad（垂直居中，上下各留 pad）
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(size, size);
        leftLp.leftMargin = pad;
        leftLp.topMargin = pad;
        leftBtn.setLayoutParams(leftLp);
        capsule.addView(leftBtn);

        // 右圆：x=pad+size+gap, y=pad
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(size, size);
        rightLp.leftMargin = pad + size + gap;
        rightLp.topMargin = pad;
        rightBtn.setLayoutParams(rightLp);
        capsule.addView(rightBtn);

        return capsule;
    }

    // 生成一个圆底箭头按钮（方向：0=左，1=右）——用 Canvas 画三角，几何绝对居中
    private View makeArrowButton(int direction, View.OnClickListener onClick, int bgColor) {
        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));

        // 圆外框
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgColor);
        container.setBackground(bg);

        // 居中箭头图标
        ArrowView arrow = new ArrowView(this, direction);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        arrowLp.gravity = Gravity.CENTER;
        arrow.setLayoutParams(arrowLp);
        container.addView(arrow);

        container.setOnClickListener(onClick);
        return container;
    }

    // 生成一个圆底刷新按钮——用 Canvas 画圆环箭头，居中（放大到 52dp）
    private View makeRefreshButton(View.OnClickListener onClick, int bgColor) {
        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)));

        // 圆外框
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgColor);
        container.setBackground(bg);

        // 居中刷新图标
        RefreshView icon = new RefreshView(this);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        container.addView(icon);

        container.setOnClickListener(onClick);
        return container;
    }

    // 自定义箭头 View：画 < / > 线条（参考图样式），边界框严格居中
    private static class ArrowView extends View {
        private final int direction; // 0=left, 1=right
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public ArrowView(Context context, int direction) {
            super(context);
            this.direction = direction;
            paint.setColor(0xFFFFFFFF);
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) * 0.22f;       // 箭头半高（比参考图略大一点的保险值）
            float d = r * 0.85f;                    // 尖/尾到中心水平距离，张角更大
            float thick = r * 0.18f;                // 线粗
            paint.setStrokeWidth(thick);

            Path path = new Path();
            // 以 View 中心 (cx, cy) 作为 < > 的折角顶点，视觉最居中
            if (direction == 0) { // 左箭头 <
                path.moveTo(cx, cy - r);
                path.lineTo(cx - d, cy);
                path.lineTo(cx, cy + r);
            } else { // 右箭头 >
                path.moveTo(cx, cy - r);
                path.lineTo(cx + d, cy);
                path.lineTo(cx, cy + r);
            }
            canvas.drawPath(path, paint);
        }
    }

    // 自定义刷新图标：标准圆环箭头（参考图样式）
    private static class RefreshView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public RefreshView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) * 0.26f;       // 圆环半径
            float thick = Math.min(w, h) * 0.06f;   // 圆环粗细

            paint.setColor(0xFFFFFFFF);
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(thick);
            paint.setStrokeCap(Paint.Cap.ROUND);

            // 约 270° 弧，缺口在右上方
            float startDeg = 60;
            float sweepDeg = 270;
            rect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(rect, startDeg, sweepDeg, false, paint);

            // 箭头：在弧终点（330°）指向顺时针
            double endRad = Math.toRadians(startDeg + sweepDeg);            // 330°
            double tangRad = Math.toRadians(startDeg + sweepDeg + 90);      // 60° 切线
            float ax = cx + r * (float) Math.cos(endRad);
            float ay = cy + r * (float) Math.sin(endRad);
            float head = thick * 2.0f;
            float tipX = ax + head * (float) Math.cos(tangRad);
            float tipY = ay + head * (float) Math.sin(tangRad);
            double perp = tangRad + Math.PI / 2;
            float halfW = thick * 1.0f;
            float b1x = ax + halfW * (float) Math.cos(perp);
            float b1y = ay + halfW * (float) Math.sin(perp);
            float b2x = ax - halfW * (float) Math.cos(perp);
            float b2y = ay - halfW * (float) Math.sin(perp);

            paint.setStyle(Paint.Style.FILL);
            Path arrow = new Path();
            arrow.moveTo(tipX, tipY);
            arrow.lineTo(b1x, b1y);
            arrow.lineTo(b2x, b2y);
            arrow.close();
            canvas.drawPath(arrow, paint);
        }
    }

    // dp 转 px，保证不同分辨率下按钮高度一致
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC_PERMISSION) return;

        PermissionRequest req = pendingPermissionRequest;
        pendingPermissionRequest = null;   // grant/deny 只能调一次，取完即清空
        if (req == null) return;

        // 麦克风/相机可能同时申请，必须校验全部结果，不能只看 grantResults[0]
        boolean allGranted = true;
        if (grantResults != null) {
            for (int g : grantResults) {
                if (g != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
        }
        if (allGranted) {
            grantMediaRequest(req);
        } else {
            Log.w(TAG, "麦克风/相机权限被拒绝，需到系统设置手动开启");
            req.deny();
        }
    }

    // 只放行音频/视频采集，其余资源一律 deny
    private void grantMediaRequest(PermissionRequest request) {
        try {
            List<String> allow = new ArrayList<>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                        || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                    allow.add(r);
                }
            }
            if (allow.isEmpty()) {
                Log.w(TAG, "请求的资源无可放行项，deny");
                request.deny();
            } else {
                Log.d(TAG, "grant: " + allow);
                request.grant(allow.toArray(new String[0]));
            }
        } catch (Exception e) {
            Log.e(TAG, "grant 失败: " + e.getMessage());
        }
    }

    // 返回键：网页能后退则后退，否则退出
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    // JS <-> 原生通信桥
    public class JSBridge {
        @JavascriptInterface
        public void showToast(String msg) {
            // JS 回调不在 UI 线程，需切回主线程
            runOnUiThread(() ->
                    Toast.makeText(WebViewActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getAppInfo() {
            return "NativeWebView/1.0";
        }
    }
}
