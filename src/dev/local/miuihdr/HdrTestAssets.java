package dev.local.miuihdr;
import android.graphics.*;import android.net.Uri;import java.io.*;import java.util.Locale;
public final class HdrTestAssets {
 public static Bitmap[] load(android.content.Context c)throws IOException{
 BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;Bitmap photo;
 try(InputStream in=c.getAssets().open("hdr-clouds.jpg")){photo=BitmapFactory.decodeStream(in,null,o);}
 if(photo==null||!photo.hasGainmap())throw new IOException("测试素材缺少增益图");
 Bitmap plain=photo.copy(Bitmap.Config.ARGB_8888,false);plain.setGainmap(null);Bitmap chart=createChart();Bitmap chartSdr=chart.copy(Bitmap.Config.ARGB_8888,false);chartSdr.setGainmap(null);return new Bitmap[]{photo,plain,chart,chartSdr};
 }
    public static String inspect(android.content.Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法解码图片");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while ((long)bounds.outWidth * bounds.outHeight / options.inSampleSize / options.inSampleSize > 16000000L) options.inSampleSize *= 2;
        Bitmap image;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { image = BitmapFactory.decodeStream(in, null, options); }
        if (image == null) throw new IOException("无法解码图片");
        try {
            String base = bounds.outWidth + " × " + bounds.outHeight + "  ·  " + bounds.outMimeType;
            if (!image.hasGainmap()) return base + "\n\n没有增益图。这张图片仅能按 SDR 显示。";
            Gainmap gm = image.getGainmap(); Bitmap map = gm.getGainmapContents(); int[] row = new int[map.getWidth()];
            int peak = 0; long nonzero = 0; boolean alpha = map.getConfig() == Bitmap.Config.ALPHA_8;
            for (int y = 0; y < map.getHeight(); y++) { map.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int p : row) { int a = p >>> 24; int v = alpha ? a : Math.max((p >>> 16) & 255, Math.max((p >>> 8) & 255, p & 255)) * a / 255;
                    peak = Math.max(peak, v); if (v > 1) nonzero++; }
            }
            float ratio = gm.getDisplayRatioForFullHdr(); boolean valid = peak > 1 && Float.isFinite(ratio) && ratio > 1.01f;
            return base + "\n\n" + (valid ? "检测到有效 HDR 增益信号。" : peak <= 1 ? "增益图接近全黑，未检测到有效高光。" : "存在增益图，但 HDR 比例没有提升。") +
                String.format(Locale.ROOT, "\n完整 HDR 显示比例：%.3f×\n增益图峰值：%d / 255\n有效区域：%.2f%%", ratio, peak, nonzero * 100.0 / map.getWidth() / map.getHeight()) +
                (options.inSampleSize > 1 ? "\n大图已缩小分析，极小高光可能遗漏。" : "") + "\n\n有效增益图表示文件含有 HDR 信息，不代表各应用的显示亮度相同。";
        } finally { image.recycle(); }
    }
    public static Bitmap createChart() {
        Bitmap b = Bitmap.createBitmap(900, 1050, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        Bitmap map = Bitmap.createBitmap(900, 1050, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b), g = new Canvas(map); c.drawColor(Color.BLACK); g.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); Paint gp = new Paint();
        for (int i = 0; i < 3; i++) {
            float top = 80 + i * 320; p.setColor(Color.rgb(180, 180, 180)); c.drawRect(170, top, 730, top + 200, p);
            int level = i == 0 ? 0 : i == 1 ? 128 : 255; gp.setColor(Color.rgb(level, level, level)); g.drawRect(170, top, 730, top + 200, gp);
            p.setColor(0xffaaaaaa); p.setTextSize(36); p.setTextAlign(Paint.Align.CENTER); c.drawText(new String[]{"1×", "2×", "4×"}[i], 450, top + 252, p);
        }
        Gainmap gm = new Gainmap(map); gm.setRatioMin(1, 1, 1); gm.setRatioMax(4, 4, 4); gm.setGamma(1, 1, 1);
        gm.setEpsilonSdr(0, 0, 0); gm.setEpsilonHdr(0, 0, 0); gm.setMinDisplayRatioForHdrTransition(1); gm.setDisplayRatioForFullHdr(4); b.setGainmap(gm); return b;
    }
}
