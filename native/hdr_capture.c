#include "hdr_capture_math.h"
/* report: HDR channel peaks[3], gain maxima[3], highlights, colored gains,
 * SDR pixels, normalization. Output is full resolution and opaque. */
JNIEXPORT jint JNICALL Java_dev_local_miuihdr_NativeBridge_captureGainmap(JNIEnv *env,jclass clazz,jobject source,jobject base,jobject gain,jfloat scale,jboolean rgb,jboolean linear,jfloatArray report) {
 jobject objects[3]={source,base,gain};AndroidBitmapInfo info[3];unsigned char *pixels[3]={0};int locked=0,result=-1;
 struct hdr_sample *lookup=NULL;float summary[10]={0,0,0,1,1,1,0,0,0,scale};
 if(!isfinite(scale)||scale<=0||scale>10000||(*env)->GetArrayLength(env,report)!=10)return -2;
 for(int i=0;i<3;i++) {
  if(AndroidBitmap_getInfo(env,objects[i],&info[i])||(info[i].flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE))goto done;
  if(info[i].format!=(i?ANDROID_BITMAP_FORMAT_RGBA_8888:ANDROID_BITMAP_FORMAT_RGBA_F16))goto done;
  if(i&&(info[i].width!=info[0].width||info[i].height!=info[0].height))goto done;
  if(AndroidBitmap_lockPixels(env,objects[i],(void **)&pixels[i])||!pixels[i])goto done;locked++;
 }
 if(!info[0].width||!info[0].height||(uint64_t)info[0].width*info[0].height>16000000)goto done;
 lookup=malloc(65536*sizeof(*lookup));if(!lookup){result=-3;goto done;}
 for(unsigned h=0;h<65536;h++)lookup[h]=hdr_sample_value(h,scale,linear);
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++) {
  uint16_t h[4];memcpy(h,pixels[0]+(size_t)y*info[0].stride+8*x,8);
  if(h[3]!=0x3c00){result=-4;goto done;}
  unsigned char *b=pixels[1]+(size_t)y*info[1].stride+4*x;
  float peak=1;int highlight=0;
  for(int c=0;c<3;c++) {
   const struct hdr_sample *p=&lookup[h[c]];
   if(!isfinite(p->value)||p->value<0||p->value>10000){result=-5;goto done;}
   summary[c]=fmaxf(summary[c],p->value);peak=fmaxf(peak,p->value);highlight|=p->highlight;
  }
  if(highlight)summary[6]++;else summary[8]++;
  for(int c=0;c<3;c++) {
   const struct hdr_sample *p=&lookup[h[c]];
   b[c]=rgb?p->base:hdr_base_byte(p->value/peak,0);
   summary[3+c]=fmaxf(summary[3+c],rgb?fmaxf(1,p->value):peak);
  }
  b[3]=255;
 }
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++) {
  uint16_t h[4];memcpy(h,pixels[0]+(size_t)y*info[0].stride+8*x,8);
  unsigned char *g=pixels[2]+(size_t)y*info[2].stride+4*x;
  float peak=1;for(int c=0;c<3;c++)peak=fmaxf(peak,lookup[h[c]].value);
  for(int c=0;c<3;c++)g[c]=hdr_gain_byte(rgb?fmaxf(1,lookup[h[c]].value):peak,summary[3+c]);
  if(g[0]!=g[1]||g[1]!=g[2])summary[7]++;g[3]=255;
 }
 (*env)->SetFloatArrayRegion(env,report,0,10,summary);result=(int)summary[6];
 done:free(lookup);for(int i=locked-1;i>=0;i--)AndroidBitmap_unlockPixels(env,objects[i]);return result;
}
