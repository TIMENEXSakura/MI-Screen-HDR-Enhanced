package dev.local.miuihdr;

import android.graphics.drawable.Drawable;

/** Scoped opacity for the crop border only, restored even if native drawing fails. */
final class CropFrameAlpha implements AutoCloseable {
 private final Drawable drawable;
 private final int original;
 private boolean closed;
 private CropFrameAlpha(Drawable drawable){this.drawable=drawable;original=drawable.getAlpha();}
 static CropFrameAlpha begin(Drawable drawable,float fraction){
  if(drawable==null||!Float.isFinite(fraction))return null;
  CropFrameAlpha state=new CropFrameAlpha(drawable);
  try{drawable.setAlpha(Math.round(state.original*Math.max(0,Math.min(1,fraction))));return state;}
  catch(RuntimeException|Error failure){state.close();throw failure;}
 }
 @Override public void close(){if(!closed){closed=true;drawable.setAlpha(original);}}
}
