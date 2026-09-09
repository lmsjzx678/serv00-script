package com.telite.cadillacscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_DELAY_SECONDS = "delay_seconds";

    private static final String CHANNEL_ID = "cadillac_capture";
    private static final int NOTIFICATION_ID = 7301;

    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean finished = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        workerThread = new HandlerThread("CadillacCaptureWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        int delaySeconds = Math.max(1, intent.getIntExtra(EXTRA_DELAY_SECONDS, 5));

        Notification notification = buildNotification("等待截图：" + delaySeconds + " 秒", false);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (resultCode == 0 || resultData == null) {
            finishWithMessage("截屏授权数据无效", false);
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                finishWithMessage("系统没有返回 MediaProjection", false);
                return START_NOT_STICKY;
            }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    cleanup();
                }
            }, worker);

            int[] size = getCaptureSize();
            int width = size[0];
            int height = size[1];
            int density = size[2];

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            virtualDisplay = projection.createVirtualDisplay(
                    "CadillacScreenshot",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

            updateNotification("请切到目标画面，" + delaySeconds + " 秒后截图");
            worker.postDelayed(() -> tryCapture(0), delaySeconds * 1000L);
        } catch (Throwable t) {
            finishWithMessage("启动截图失败：" + t.getClass().getSimpleName() + " - " + safe(t.getMessage()), false);
        }

        return START_NOT_STICKY;
    }

    private int[] getCaptureSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int width;
        int height;
        int density = getResources().getDisplayMetrics().densityDpi;

        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            width = b.width();
            height = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            width = dm.widthPixels;
            height = dm.heightPixels;
            density = dm.densityDpi;
        }
        return new int[]{width, height, density};
    }

    private void tryCapture(int attempt) {
        if (finished || imageReader == null) return;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                if (attempt < 20) {
                    worker.postDelayed(() -> tryCapture(attempt + 1), 150L);
                } else {
                    finishWithMessage("没有取得屏幕帧，车机可能禁止 MediaProjection", false);
                }
                return;
            }

            Bitmap bitmap = imageToBitmap(image);
            image.close();
            image = null;

            String saved = saveBitmap(bitmap);
            bitmap.recycle();
            finishWithMessage("截图已保存：" + saved, true);
        } catch (Throwable t) {
            if (image != null) image.close();
            finishWithMessage("截图失败：" + t.getClass().getSimpleName() + " - " + safe(t.getMessage()), false);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private String saveBitmap(Bitmap bitmap) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "Cadillac_" + stamp + ".png";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CadillacScreenshots");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert 返回 null");
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new IllegalStateException("PNG 写入失败");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return "Pictures/CadillacScreenshots/" + fileName;
        } else {
            File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File dir = new File(root, "CadillacScreenshots");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建截图目录");
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new IllegalStateException("PNG 写入失败");
                }
            }
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file));
            sendBroadcast(scan);
            return file.getAbsolutePath();
        }
    }

    private void finishWithMessage(String message, boolean success) {
        if (finished) return;
        finished = true;
        updateNotification(message);
        getSharedPreferences("capture", MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_time", System.currentTimeMillis())
                .apply();
        worker.postDelayed(() -> {
            cleanup();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
            else stopForeground(false);
            stopSelf();
        }, success ? 2500L : 4500L);
    }

    private Notification buildNotification(String text, boolean done) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("凯迪车机截图")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(!done)
                .setAutoCancel(done);
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text, finished));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "车机截图", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("车机截图倒计时与保存结果");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(c);
        }
    }

    private void cleanup() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {}
        virtualDisplay = null;
        try {
            if (imageReader != null) imageReader.close();
        } catch (Throwable ignored) {}
        imageReader = null;
        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {}
        projection = null;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
