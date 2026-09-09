package com.telite.cadillacscreenshot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int REQ_NOTIFY = 1003;

    private MediaProjectionManager projectionManager;
    private int pendingDelaySeconds = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        maybeRequestNotificationPermission();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8, 20, 39));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(30), dp(34), dp(30));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("凯迪车机截图");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("不需要悬浮窗。授权后程序自动退到后台，倒计时结束直接截取车机原始画面。\n截图保存为 PNG，不压缩。\n\n第一次截图时，系统会弹出“开始录制/共享屏幕”授权框，请选择【整个屏幕】并允许。");
        subtitle.setTextColor(Color.rgb(200, 215, 235));
        subtitle.setTextSize(17);
        subtitle.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(16);
        root.addView(subtitle, sp);

        Button five = makeButton("5 秒后截图");
        five.setOnClickListener(v -> beginCapture(5));
        root.addView(five, buttonParams());

        Button ten = makeButton("10 秒后截图");
        ten.setOnClickListener(v -> beginCapture(10));
        root.addView(ten, buttonParams());

        Button twenty = makeButton("20 秒后截图");
        twenty.setOnClickListener(v -> beginCapture(20));
        root.addView(twenty, buttonParams());

        TextView how = new TextView(this);
        how.setText("使用方法\n① 先打开本软件，点“10秒后截图”\n② 系统询问是否允许截屏 → 选择“整个屏幕”→ 允许\n③ 本软件自动退到后台\n④ 你马上切到需要截图的车机壁纸/仪表页面\n⑤ 倒计时结束自动保存\n\n保存目录：Pictures/CadillacScreenshots\n文件名示例：Cadillac_20260909_121530.png");
        how.setTextColor(Color.WHITE);
        how.setTextSize(17);
        how.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(24);
        root.addView(how, hp);

        TextView warning = new TextView(this);
        warning.setText("说明：如果凯迪拉克系统把某个区域标记为安全画面（FLAG_SECURE），Android 会把那一块截成黑色；普通应用不能绕过系统安全限制。若车机把仪表与中控当成不同物理显示器，本软件只能截到系统允许投屏的显示器。");
        warning.setTextColor(Color.rgb(255, 201, 120));
        warning.setTextSize(15);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(24);
        root.addView(warning, wp);

        setContentView(scroll);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(47, 128, 237));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(62));
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(66));
        p.topMargin = dp(18);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void beginCapture(int delaySeconds) {
        pendingDelaySeconds = delaySeconds;
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        launchCaptureConsent();
    }

    private void launchCaptureConsent() {
        try {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动系统截屏授权：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "你取消了截屏授权", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_DELAY_SECONDS, pendingDelaySeconds);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        Toast.makeText(this, pendingDelaySeconds + " 秒后自动截图，请马上切到目标画面", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCaptureConsent();
            } else {
                Toast.makeText(this, "Android 9及以下需要存储权限才能保存截图", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }
}
