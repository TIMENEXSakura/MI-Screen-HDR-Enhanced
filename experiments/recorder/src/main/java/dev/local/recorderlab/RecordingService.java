package dev.local.recorderlab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.Surface;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class RecordingService extends Service {
    static volatile boolean active;
    static volatile String status = "准备就绪";
    private volatile boolean stopping;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { stopping = true; return START_NOT_STICKY; }
        if (active) return START_NOT_STICKY;
        active = true; stopping = false; status = "正在初始化录屏后端…";
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("recording", "测试录屏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "recording").setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("录屏后端测试").setContentText("正在录屏 · 点按停止按钮保存").setOngoing(true)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止并保存", stop).build()).build();
        try {
            startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) { active = false; status = "无法启动录屏服务：" + e; stopSelf(); return START_NOT_STICKY; }
        new Thread(() -> record(intent), "recorder-encode-drain").start();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() { stopping = true; super.onDestroy(); }

    private MediaCodec encoder(MediaFormat format, int width, int height, int fps) throws Exception {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        Arrays.sort(infos, Comparator.comparing(MediaCodecInfo::getName));
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
            if (!Arrays.asList(info.getSupportedTypes()).contains("video/avc")) continue;
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
            if (!caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps)) continue;
            boolean surface = false;
            for (int color : caps.colorFormats) if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
            if (!surface) continue;
            MediaCodec codec = MediaCodec.createByCodecName(info.getName());
            try { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); return codec; }
            catch (Exception e) { codec.release(); throw e; }
        }
        throw new IllegalStateException("未找到支持当前分辨率和帧率的硬件 AVC 编码器，请降低设置。");
    }
    private Uri output(String name, boolean video) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies/" : "Download/") + "MI-Recorder-Test");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法创建输出文件");
        return uri;
    }
    private void publish(Uri uri) {
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
    }
    private void record(Intent intent) {
        boolean vulkan = intent.getBooleanExtra("vulkan", false);
        int width = intent.getIntExtra("width", 1600), height = intent.getIntExtra("height", 720);
        int fps = intent.getIntExtra("fps", 60), seconds = intent.getIntExtra("seconds", 60);
        String backend = vulkan ? "Vulkan" : "GLES";
        String name = "Recorder-" + backend + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        MediaCodec codec = null; MediaMuxer muxer = null; ParcelFileDescriptor fd = null;
        MediaProjection projection = null; VirtualDisplay display = null;
        Surface encoderSurface = null, input = null; long engine = 0;
        Uri video = null; boolean muxing = false, saved = false;
        FutureTask<String> stoppingEngine = null;
        long frames = 0, firstPts = -1, lastPts = -1, ptsErrors = 0;
        long begin = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime();
        String codecName = "unknown", nativeStats = "{}", error = "";
        PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorderlab:capture");
        try {
            wake.acquire((seconds + 30L) * 1000);
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, width * height >= 2_000_000 ? 16_000_000 : 8_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            codec = encoder(format, width, height, fps); codecName = codec.getName();
            encoderSurface = codec.createInputSurface(); codec.start();
            engine = NativeBridge.create(encoderSurface, width, height, fps, vulkan);
            input = NativeBridge.inputSurface(engine);
            Intent consent = intent.getParcelableExtra("consent", Intent.class);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopping = true; }
            }, new Handler(getMainLooper()));
            video = output(name + ".mp4", true);
            fd = getContentResolver().openFileDescriptor(video, "rw");
            if (fd == null) throw new IllegalStateException("无法写入视频文件");
            muxer = new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            display = projection.createVirtualDisplay("RecorderLab-" + backend, width, height,
                getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null);
            begin = SystemClock.elapsedRealtime(); cpu = android.os.Process.getElapsedCpuTime();
            long lastStats = 0, eosAt = 0, stopRequestedAt = 0; int track = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (eosAt == 0 && stoppingEngine == null && (stopping || now - begin >= seconds * 1000L)) {
                    display.release(); display = null;
                    long closing = engine; engine = 0; stopRequestedAt = now;
                    stoppingEngine = new FutureTask<>(() -> NativeBridge.stop(closing));
                    new Thread(stoppingEngine, "recorder-finish-gpu").start();
                }
                if (stoppingEngine != null && stoppingEngine.isDone() && eosAt == 0) {
                    nativeStats = stoppingEngine.get();
                    codec.signalEndOfInputStream(); eosAt = now;
                }
                if (stopRequestedAt != 0 && eosAt == 0 && now - stopRequestedAt > 10000)
                    throw new IllegalStateException("GPU 停止等待超时，请保留报告。");
                if (engine != 0 && now - lastStats >= 1000) {
                    nativeStats = NativeBridge.stats(engine); lastStats = now;
                    String nativeError = new JSONObject(nativeStats).optString("error");
                    if (!nativeError.isEmpty()) throw new IllegalStateException(nativeError);
                    status = backend + " 正在录制 · " + (now - begin)/1000 + " 秒\n已编码 " + frames + " 帧\n" + width + " × " + height + " / " + fps + " FPS 上限";
                }
                if (eosAt != 0 && now - eosAt > 5000) throw new IllegalStateException("编码器结束超时，已保留诊断报告。");
                if (frames == 0 && now - begin > 12000) throw new IllegalStateException("12 秒内未收到编码画面，请查看报告中的后端状态。");
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IllegalStateException("编码格式在录制中变化");
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxing = true;
                } else if (index >= 0) {
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (!muxing) throw new IllegalStateException("编码器未提供视频格式");
                            ByteBuffer data = codec.getOutputBuffer(index);
                            if (data == null) throw new IllegalStateException("编码输出为空");
                            if (firstPts < 0) firstPts = info.presentationTimeUs;
                            long relative = info.presentationTimeUs - firstPts;
                            if (relative <= lastPts) { ptsErrors++; throw new IllegalStateException("编码时间戳未递增，停止保存异常时间轴。"); }
                            lastPts = relative; info.presentationTimeUs = relative;
                            data.position(info.offset); data.limit(info.offset + info.size);
                            muxer.writeSampleData(track, data, info); frames++;
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Throwable e) {
            error = e.toString();
        } finally {
            if (display != null) try { display.release(); } catch (Exception ignored) { }
            // Abort codec input first on errors so a blocked GPU presentation can exit.
            if (!error.isBlank() && codec != null) try { codec.stop(); } catch (Exception ignored) { }
            if (engine != 0) try { nativeStats = NativeBridge.stop(engine); } catch (Throwable e) { error += "\n" + e; }
            if (stoppingEngine != null) try { nativeStats = stoppingEngine.get(5, TimeUnit.SECONDS); } catch (Exception e) { error += "\n" + e; }
            if (projection != null) try { projection.stop(); } catch (Exception ignored) { }
            if (input != null) input.release();
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            if (encoderSurface != null) encoderSurface.release();
            if (muxer != null) {
                try { if (muxing && frames > 0) { muxer.stop(); saved = true; } } catch (Exception e) { error += "\n保存失败：" + e; }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (fd != null) try { fd.close(); } catch (Exception ignored) { }
            if (video != null) try { if (saved) publish(video); else getContentResolver().delete(video, null, null); } catch (Exception e) { error += "\n" + e; }
            if (wake.isHeld()) wake.release();
            try { String nativeError = new JSONObject(nativeStats).optString("error"); if (!nativeError.isEmpty()) error += "\n" + nativeError; } catch (Exception ignored) { }
            long elapsed = Math.max(1, SystemClock.elapsedRealtime() - begin);
            String report = "录屏后端对照测试 0.1\n所选后端：" + backend + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL +
                "\n系统：" + Build.VERSION.RELEASE + " / " + Build.FINGERPRINT +
                "\n系统底层状态（最近主动查询）：" + SystemBackend.snapshot +
                "\n规格：" + width + "x" + height + "，帧率上限 " + fps + "\n编码器：" + codecName +
                "\n范围：无声 SDR / AVC；不改变系统合成后端；无自动 GLES 回退。" +
                "\n视频有效保存：" + saved + "\n编码帧数：" + frames + "\n视频时间轴微秒：" + lastPts +
                "\n平均编码帧率（视频时间轴）：" + (lastPts > 0 ? (frames-1)*1_000_000.0/lastPts : 0) +
                "\n测试进程平均 CPU（100% = 一个 CPU 核心）：" + (android.os.Process.getElapsedCpuTime()-cpu)*100.0/elapsed +
                "\n编码时间戳错误：" + ptsErrors + "\n原生处理统计：" + nativeStats +
                "\n错误：" + error + "\n说明：统计不包含游戏或系统进程的 CPU/GPU 开销，不能当成游戏帧率。\n";
            try {
                Uri reportUri = output(name + ".txt", false);
                try (OutputStream stream = getContentResolver().openOutputStream(reportUri)) {
                    if (stream == null) throw new IllegalStateException("无法写入报告");
                    stream.write(report.getBytes(StandardCharsets.UTF_8));
                }
                publish(reportUri);
            } catch (Exception e) { error += "\n报告保存失败：" + e; }
            status = (error.isBlank() ? "测试完成" : "测试结束：" + error) +
                "\n" + (saved ? "视频已保存：Movies/MI-Recorder-Test/" + name + ".mp4" : "未生成有效视频") +
                "\n报告：Download/MI-Recorder-Test/" + name + ".txt";
            active = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
}
