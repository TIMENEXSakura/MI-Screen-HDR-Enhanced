package dev.local.miuihdr;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
public final class CalibrationActivity extends Activity {
 @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setColorMode(2);getWindow().setDesiredHdrHeadroom(4);getWindow().getDecorView().setSystemUiVisibility(5894);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  setContentView(new View(this){final Bitmap pattern=create();final Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);@Override protected void onDraw(Canvas c){c.drawColor(Color.BLACK);c.drawBitmap(pattern,null,new RectF(0,0,getWidth(),getHeight()),p);}});
 }
 private static Bitmap create(){int w=600,h=1000;Bitmap base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,true,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));Canvas c=new Canvas(base);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Bitmap gain=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas g=new Canvas(gain);g.drawColor(Color.BLACK);Paint gp=new Paint();
  for(int k=0;k<3;k++){int y=160+k*250;p.setColor(Color.rgb(180,180,180));c.drawRect(150,y,450,y+130,p);int level=k==0?0:k==1?128:255;gp.setColor(Color.rgb(level,level,level));g.drawRect(150,y,450,y+130,gp);p.setColor(0xff888888);p.setTextSize(24);c.drawText(new String[]{"1x","2x","4x"}[k],270,y+175,p);}
  Gainmap gm=new Gainmap(gain);gm.setRatioMin(1,1,1);gm.setRatioMax(4,4,4);gm.setGamma(1,1,1);gm.setEpsilonSdr(0,0,0);gm.setEpsilonHdr(0,0,0);gm.setMinDisplayRatioForHdrTransition(1);gm.setDisplayRatioForFullHdr(4);base.setGainmap(gm);return base;
 }
}
