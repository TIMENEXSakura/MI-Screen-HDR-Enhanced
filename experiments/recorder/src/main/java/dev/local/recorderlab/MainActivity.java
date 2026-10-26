package dev.local.recorderlab;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private RadioGroup backend;
    private Spinner resolution, fps, orientation, duration;
    private TextView status;
    private Button start;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(RecordingService.status);
            start.setEnabled(!RecordingService.active);
            handler.postDelayed(this, 1000);
        }
    };
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout layout, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(10), 0, dp(10)); layout.addView(view);
    }
    private Spinner spinner(LinearLayout layout, String title, String[] values) {
        text(layout, title, 15); Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        layout.addView(s); return s;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(layout); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        text(layout, "录屏后端测试", 28);
        text(layout, "独立实验应用 · 0.1\n仅测试无声 SDR 录屏。不会替换小米录屏或修改系统库。", 14);
        backend = new RadioGroup(this); backend.setOrientation(RadioGroup.HORIZONTAL);
        for (int i=0;i<2;i++) { RadioButton b = new RadioButton(this); b.setId(View.generateViewId()); b.setText(i==0 ? "GLES 3" : "Vulkan"); backend.addView(b); }
        backend.check(backend.getChildAt(0).getId()); layout.addView(backend);
        text(layout, "切换应用内的画面接收与 GPU 处理后端。两种方式共用虚拟显示和硬件编码器；Vulkan 不可用时会报错，不会自动换成 GLES。", 14);
        resolution = spinner(layout, "短边分辨率", new String[]{"720 像素", "1080 像素"});
        fps = spinner(layout, "最高录制帧率", new String[]{"60 FPS", "30 FPS"});
        orientation = spinner(layout, "输出方向（进入游戏前选择）", new String[]{"横屏", "竖屏"});
        duration = spinner(layout, "自动停止", new String[]{"60 秒", "120 秒", "30 秒"});
        start = new Button(this); start.setText("开始测试录屏"); layout.addView(start);
        start.setOnClickListener(v -> {
            if (RecordingService.active) return;
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 20);
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), 21);
        });
        Button stop = new Button(this); stop.setText("停止并保存"); layout.addView(stop);
        stop.setOnClickListener(v -> { if (RecordingService.active) startService(new Intent(this, RecordingService.class).setAction("stop")); });
        text(layout, "视频：Movies/MI-Recorder-Test\n报告：Download/MI-Recorder-Test\n建议相同游戏场景分别录制。报告中的帧率属于录屏，不代表游戏帧率。", 14);
        status = new TextView(this); status.setTextIsSelectable(true); layout.addView(status);
        text(layout, "", 12); new SystemBackend(this, layout);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 21 || result != RESULT_OK || data == null || RecordingService.active) return;
        int shortEdge = resolution.getSelectedItemPosition() == 0 ? 720 : 1080;
        android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        double ratio = (double)Math.max(bounds.width(), bounds.height()) / Math.min(bounds.width(), bounds.height());
        int longEdge = ((int)Math.round(shortEdge * ratio) + 1) & ~1;
        boolean landscape = orientation.getSelectedItemPosition() == 0;
        Intent intent = new Intent(this, RecordingService.class).putExtra("consent", data).putExtra("result", result)
            .putExtra("vulkan", backend.getCheckedRadioButtonId() == backend.getChildAt(1).getId())
            .putExtra("width", landscape ? longEdge : shortEdge).putExtra("height", landscape ? shortEdge : longEdge)
            .putExtra("fps", fps.getSelectedItemPosition() == 0 ? 60 : 30)
            .putExtra("seconds", new int[]{60,120,30}[duration.getSelectedItemPosition()]);
        startForegroundService(intent);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
