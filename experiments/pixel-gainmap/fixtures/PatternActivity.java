package dev.local.hdrprecision;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import java.io.*;

/** Device-only diagnostic fixture, separate from the user's installed module. */
public final class PatternActivity extends Activity {
    Bitmap source;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setColorMode(2);
        getWindow().setDesiredHdrHeadroom(getIntent().getFloatExtra("headroom", 4f));
        getWindow().getDecorView().setSystemUiVisibility(5894);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new View(this) {
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                if (w <= 0 || h <= 0) return;
                source = create(w, h);
                try (OutputStream out = new FileOutputStream(new File(getExternalFilesDir(null), "source.png"))) {
                    source.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override protected void onDraw(Canvas canvas) {
                if (source != null) canvas.drawBitmap(source, 0, 0, null);
            }
        });
    }
    Bitmap create(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        b.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint();
        // Grey surround, dark/white neutral bands, and a central lighter image area.
        canvas.drawColor(Color.rgb(136,136,136));
        p.setColor(Color.BLACK); canvas.drawRect(0,0,w,h/8,p);
        p.setColor(Color.WHITE); canvas.drawRect(0,7*h/8,w,h,p);
        p.setColor(Color.rgb(180,180,180)); canvas.drawRect(w/4,h/4,3*w/4,3*h/4,p);
        Bitmap map = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        map.setDensity(Bitmap.DENSITY_NONE);
        Canvas gc = new Canvas(map); gc.drawColor(Color.BLACK); p.setColor(Color.WHITE);
        int cx=w/2, cy=h/2;
        gc.drawRect(cx-32,cy-32,cx+32,cy+32,p);
        // Narrow features exercise positional precision and clipping.
        gc.drawRect(w/4+20,h/4+20,w/4+21,3*h/4-20,p);
        gc.drawRect(w/4+40,h/4+40,3*w/4-40,h/4+41,p);
        gc.drawRect(3*w/4-20,3*h/4-20,3*w/4-19,3*h/4-19,p);
        Gainmap gain = new Gainmap(map);
        gain.setRatioMin(1,1,1); gain.setRatioMax(4,4,4); gain.setGamma(1,1,1);
        gain.setEpsilonSdr(0,0,0); gain.setEpsilonHdr(0,0,0);
        gain.setMinDisplayRatioForHdrTransition(1); gain.setDisplayRatioForFullHdr(4);
        b.setGainmap(gain);
        return b;
    }
}
