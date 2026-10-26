package dev.local.miuihdr;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.util.Half;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual device GPU shader -> Bitmap Gainmap -> editor -> JPEG -> decode.
 * Uses synthetic buffers only; never captures or displays the user's screen. */
public final class RgbGainmapPipelineTest {
    static int checks;
    static final int W=15,H=9;
    static final float EPS=1f/64;
    static void check(boolean v,String text){if(!v)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    static byte[] pixels(Bitmap b){ByteBuffer bytes=ByteBuffer.allocate(b.getByteCount());b.copyPixelsToBuffer(bytes);return bytes.array();}
    static float gain(int x,int y,int c){return new float[]{4,2,1}[(x+y+c)%3];}
    static Bitmap fixture(boolean hdr){
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.RGBA_F16,false,ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB));
        ByteBuffer bytes=ByteBuffer.allocate(b.getByteCount()).order(ByteOrder.nativeOrder());
        for(int y=0;y<H;y++){
            bytes.position(y*b.getRowBytes());
            for(int x=0;x<W;x++){
                for(int c=0;c<3;c++)bytes.putShort(Half.toHalf(hdr?(.25f+EPS)*gain(x,y,c)-EPS:.25f));
                bytes.putShort(Half.toHalf(1f));
            }
        }
        bytes.rewind();b.copyPixelsFromBuffer(bytes);return b;
    }
    static Bitmap render(String code,Bitmap base,Bitmap full,float maxLog)throws Exception {
        RuntimeShader shader=new RuntimeShader(code);
        shader.setFloatUniform("mapMaxLog2",maxLog);
        shader.setInputBuffer("sdr",new BitmapShader(base,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        shader.setInputBuffer("hdr",new BitmapShader(full,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        RenderNode node=new RenderNode("RGB gainmap regression");node.setPosition(0,0,W,H);
        RecordingCanvas canvas=node.beginRecording();Paint p=new Paint();p.setShader(shader);p.setBlendMode(BlendMode.SRC);canvas.drawRect(0,0,W,H,p);node.endRecording();
        try(HardwareBuffer buffer=HardwareBuffer.create(W,H,HardwareBuffer.RGBA_8888,1,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            HardwareBufferRenderer renderer=new HardwareBufferRenderer(buffer)){
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
            renderer.setContentRoot(node);
            renderer.obtainRenderRequest().setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)).draw(Runnable::run,result->{
                try(SyncFence fence=result.getFence()){
                    if(result.getStatus()!=HardwareBufferRenderer.RenderResult.SUCCESS||!fence.await(Duration.ofSeconds(5)))throw new AssertionError("GPU draw/fence failed");
                }catch(Throwable e){error.set(e);}finally{done.countDown();}
            });
            if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("GPU draw timed out");
            if(error.get()!=null)throw new AssertionError("GPU render failed",error.get());
            Bitmap wrapped=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
            if(wrapped==null)throw new AssertionError("GPU buffer wrap failed");
            try{Bitmap result=wrapped.copy(Bitmap.Config.ARGB_8888,false);result.setHasAlpha(false);return result;}
            finally{wrapped.recycle();}
        }finally{node.discardDisplayList();}
    }
    static Bitmap attach(Bitmap map){
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.SRGB));
        b.eraseColor(Color.rgb(137,137,137));Gainmap g=new Gainmap(map);
        g.setRatioMin(1,1,1);g.setRatioMax(4,4,4);g.setGamma(1,1,1);
        g.setEpsilonSdr(EPS,EPS,EPS);g.setEpsilonHdr(EPS,EPS,EPS);
        g.setMinDisplayRatioForHdrTransition(1);g.setDisplayRatioForFullHdr(4);b.setGainmap(g);return b;
    }
    static Bitmap frameworkWrap(Bitmap source,Bitmap map)throws Exception{
        Bitmap hwBase=source.copy(Bitmap.Config.HARDWARE,false),hwMap=map.copy(Bitmap.Config.HARDWARE,false);
        try(HardwareBuffer baseBuffer=hwBase.getHardwareBuffer();HardwareBuffer mapBuffer=hwMap.getHardwareBuffer()){
            Class<?> type=Class.forName("android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer");
            var factory=type.getDeclaredMethod("createFromNative",HardwareBuffer.class,int.class,boolean.class,boolean.class,HardwareBuffer.class,float.class);
            factory.setAccessible(true);
            Object wrapper=factory.invoke(null,baseBuffer,0x8810000,false,true,mapBuffer,4f);
            Bitmap wrapped=(Bitmap)type.getDeclaredMethod("asBitmap").invoke(wrapper);
            if(wrapped==null)throw new AssertionError("framework returned no bitmap");
            try{
                Bitmap result=wrapped.copy(Bitmap.Config.ARGB_8888,false);
                Gainmap gm=result.getGainmap();
                if(gm!=null&&gm.getGainmapContents().getConfig()==Bitmap.Config.HARDWARE)
                    result.setGainmap(new Gainmap(gm,gm.getGainmapContents().copy(Bitmap.Config.ARGB_8888,false)));
                return result;
            }finally{wrapped.recycle();}
        }finally{hwBase.recycle();hwMap.recycle();}
    }
    static double linear(int v){double x=v/255.0;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}
    static void verify(Bitmap b,int top,String label,double tolerance){
        verify(b,top,label,tolerance,true);
    }
    static void verify(Bitmap b,int top,String label,double tolerance,boolean requireDistinctMap){
        check(b!=null&&b.hasGainmap(),label+" has gainmap");Gainmap g=b.getGainmap();Bitmap m=g.getGainmapContents();
        check(m.getConfig()!=Bitmap.Config.ALPHA_8,label+" retains RGB storage");
        byte[] base=pixels(b),map=pixels(m);float[] lo=g.getRatioMin(),hi=g.getRatioMax(),gamma=g.getGamma(),es=g.getEpsilonSdr(),eh=g.getEpsilonHdr();
        int colored=0;double worst=0;
        for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++){
            int a=y*m.getRowBytes()+4*x,z=y*b.getRowBytes()+4*x;
            if(map[a]!=map[a+1]||map[a+1]!=map[a+2])colored++;
            for(int c=0;c<3;c++){
                double v=Math.pow((map[a+c]&255)/255.0,gamma[c]);
                double recovered=(linear(base[z+c]&255)+es[c])*Math.exp(Math.log(lo[c])+(Math.log(hi[c])-Math.log(lo[c]))*v)-eh[c];
                double wanted=(.25+EPS)*gain(x,y+top,c)-EPS;
                worst=Math.max(worst,Math.abs(recovered-wanted));
            }
        }
        System.out.println(label+" coloredGainPixels="+colored);
        if(requireDistinctMap)check(colored==b.getWidth()*b.getHeight(),label+" retains per-pixel channel differences");
        System.out.println(label+" maxLinearRgbError="+worst);
        check(worst<tolerance,label+" reconstructs independent RGB radiance");
    }
    public static void main(String[] args)throws Exception{
        try{run(args);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}
        // app_process has no Activity/Looper lifecycle. Exit through the VM,
        // rather than racing native library destructors against RenderThread.
        System.exit(0);
    }
    static void run(String[] args)throws Exception{
        System.load(args[0]);NativeBridge.loaded=true;
        String original=Files.readString(Paths.get(args[1])),candidate=Files.readString(Paths.get(args[2]));
        Bitmap sdr=fixture(false),hdr=fixture(true),oldMap=render(original,sdr,hdr,2),rgbMap=render(candidate,sdr,hdr,2);
        byte[] old=pixels(oldMap),rgb=pixels(rgbMap);boolean scalar=true,distinct=true,opaque=true;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int a=y*oldMap.getRowBytes()+x*4,b=y*rgbMap.getRowBytes()+x*4;
            scalar&=old[a]==old[a+1]&&old[a+1]==old[a+2];
            distinct&=rgb[b]!=rgb[b+1]&&rgb[b+1]!=rgb[b+2]&&rgb[b]!=rgb[b+2];opaque&=(rgb[b+3]&255)==255;
        }
        check(scalar,"original system shader reproduces loss of RGB gain differences");
        check(distinct&&opaque,"replacement GPU shader produces distinct R/G/B and opaque alpha");
        int[] channels=new int[3];
        check(NativeBridge.gainmapChannels(oldMap,channels)&&channels[0]==3&&channels[2]==0,"diagnostic distinguishes gray data in RGB storage");
        check(NativeBridge.gainmapChannels(rgbMap,channels)&&channels[0]==3&&channels[2]==W*H,"diagnostic counts actual RGB differences");
        Bitmap alpha=Bitmap.createBitmap(2,2,Bitmap.Config.ALPHA_8);
        check(NativeBridge.gainmapChannels(alpha,channels)&&channels[0]==1&&channels[1]==4,"diagnostic distinguishes single-channel gainmap");alpha.recycle();
        Bitmap full=attach(rgbMap);verify(full,0,"GPU output",.01);
        Bitmap framework=frameworkWrap(full,rgbMap);verify(framework,0,"Xiaomi screenshot buffer bridge",.01);framework.recycle();
        Bitmap crop=Bitmap.createBitmap(W,5,Bitmap.Config.ARGB_8888,false,full.getColorSpace());crop.eraseColor(Color.rgb(137,137,137));
        check(EditorGainmap.restore(full,crop,2),"editor crop restores gainmap");verify(crop,2,"editor crop",.01);
        for(Bitmap image:new Bitmap[]{full,crop}){
            byte[] jpg=HdrJpeg444.encode(image);Bitmap decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
            verify(decoded,image==full?0:2,"JPEG roundtrip "+image.getHeight(),.035);decoded.recycle();
        }
        Bitmap retained=GainmapPrecision.tryApply(full,true);
        check(retained==full,"RGB retention bypasses optional chromatic rebasing");
        byte[] jpg=HdrJpeg444.encode(retained);Bitmap decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
        verify(decoded,0,"RGB mode with precision enabled",.035);decoded.recycle();
        // Rebasing can intentionally move color into the base, so judge it by
        // reconstructed radiance rather than equating a gray map with lost HDR.
        Bitmap rebased=GainmapPrecision.apply(full);verify(rebased,0,"ordinary precision rebase",.02,false);
        jpg=HdrJpeg444.encode(rebased);decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
        verify(decoded,0,"ordinary rebased JPEG",.045,false);decoded.recycle();
        Bitmap neutral=render(candidate,sdr,sdr,2);boolean black=true;byte[] n=pixels(neutral);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int a=y*neutral.getRowBytes()+4*x;black&=n[a]==0&&n[a+1]==0&&n[a+2]==0;}
        check(black,"identical SDR/HDR buffers produce no false gain");neutral.recycle();
        Bitmap zero=render(candidate,sdr,hdr,0);n=pixels(zero);black=true;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int a=y*zero.getRowBytes()+4*x;black&=n[a]==0&&n[a+1]==0&&n[a+2]==0;}
        check(black,"zero headroom avoids undefined division");zero.recycle();
        sdr.recycle();hdr.recycle();oldMap.recycle();full.recycle();crop.recycle();rebased.recycle();
        System.out.println("RGB_GAINMAP_PIPELINE_CHECKS="+checks);
    }
}
