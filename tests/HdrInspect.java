import java.lang.reflect.*;
import java.util.Arrays;
import java.io.*;
public class HdrInspect {
 public static void main(String[] args) throws Exception {
  Class<?> bf=Class.forName("android.graphics.BitmapFactory"), bc=Class.forName("android.graphics.Bitmap");
  for(String path:args) {
   System.out.println("FILE="+path);
   Object b=bf.getMethod("decodeFile",String.class).invoke(null,path);
   if(b==null){System.out.println("DECODE=null");continue;}
   for(String m:new String[]{"getWidth","getHeight","getConfig","getColorSpace","hasGainmap","hasAlpha"})
    try{System.out.println(m+"="+bc.getMethod(m).invoke(b));}catch(Exception e){System.out.println(e);}
   Object gm=bc.getMethod("getGainmap").invoke(b);
   if(gm!=null){
    for(Method m:gm.getClass().getMethods())if(m.getParameterTypes().length==0 && (m.getName().startsWith("getRatio")||m.getName().startsWith("getGamma")||m.getName().startsWith("getEpsilon")||m.getName().contains("DisplayRatio"))){
     Object v=m.invoke(gm);System.out.println(m.getName()+"="+(v instanceof float[]?Arrays.toString((float[])v):v));
    }
    Object contents=gm.getClass().getMethod("getGainmapContents").invoke(gm);
    System.out.println("gainmap="+bc.getMethod("getWidth").invoke(contents)+"x"+bc.getMethod("getHeight").invoke(contents));
    for(String n:new String[]{"getConfig","getColorSpace","hasAlpha","isPremultiplied","getRowBytes"})System.out.println("GM_"+n+"="+bc.getMethod(n).invoke(contents));
    int gw=(Integer)bc.getMethod("getWidth").invoke(contents), gh=(Integer)bc.getMethod("getHeight").invoke(contents);
    boolean alphaOnly=String.valueOf(bc.getMethod("getConfig").invoke(contents)).equals("ALPHA_8");
    int[] row=new int[gw];int peak=0;long sum=0,nonzero=0;
    Method getPixels=bc.getMethod("getPixels",int[].class,int.class,int.class,int.class,int.class,int.class,int.class);
    for(int y=0;y<gh;y++){
     getPixels.invoke(contents,row,0,gw,0,y,gw,1);
     for(int pixel:row){int alpha=pixel>>>24;int value=alphaOnly?alpha:Math.max((pixel>>>16)&255,Math.max((pixel>>>8)&255,pixel&255))*alpha/255;peak=Math.max(peak,value);sum+=value;if(value>0)nonzero++;}
    }
    System.out.println("GM_SIGNAL max="+peak+" nonzero="+nonzero+" total="+((long)gw*gh)+" mean="+((double)sum/gw/gh));


   }
   bc.getMethod("recycle").invoke(b);
  }
 }
}
