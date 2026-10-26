#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdatomic.h>
#include <dlfcn.h>
#include <link.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO,"MiuiHdrNative",__VA_ARGS__)
typedef int (*HookFn)(void *,void *,void **);
typedef void (*LoadedFn)(const char *,void *);
typedef struct {uint32_t version;HookFn hook_func;int (*unhook_func)(void *);} NativeAPIEntries;
static HookFn hook_func;
static int (*original_flags)(int,int);
static atomic_int installed=0;
/* The recorder is also in the module scope. Only the screenshot Java entry
 * requests this HDR gate; loading the native API must not change recording. */
static atomic_int gate_requested=0;
static int keep_hdr(int flags,int mask){
 if((mask&0x1000000)&&(flags&0x1000000)){flags&=~0x1000000;LOG("Native HDR gate intercepted; mask=0x%x",mask);}
 return original_flags(flags,mask);
}
static const char gate_symbol[]="_ZN7android21SurfaceComposerClient17setMiHdrInfoFlagsEii";
static atomic_flag resolving=ATOMIC_FLAG_INIT;
struct target_check {void *address;int executable;};
static int validate_target(struct dl_phdr_info *info,size_t size,void *opaque){
 struct target_check *check=opaque;uintptr_t address=(uintptr_t)check->address;
 for(int i=0;i<info->dlpi_phnum;i++){
  const ElfW(Phdr) *ph=&info->dlpi_phdr[i];uintptr_t start=info->dlpi_addr+ph->p_vaddr;
  if(ph->p_type==PT_LOAD&&(ph->p_flags&PF_X)&&address>=start&&address-start<ph->p_memsz){check->executable=1;return 1;}
 }return 0;
}
static int find_gate(struct dl_phdr_info *info,size_t size,void *opaque){
 if(!info->dlpi_name||!*info->dlpi_name)return 0;
 void *handle=dlopen(info->dlpi_name,RTLD_NOW|RTLD_NOLOAD);if(!handle)return 0;
 void *target=dlsym(handle,gate_symbol);dlclose(handle);
 if(target){*(void **)opaque=target;return 1;}return 0;
}
static int install_gate(void *loaded){
 if(atomic_load(&installed))return 1;if(!hook_func)return -1;
 if(atomic_flag_test_and_set(&resolving))return -2;
 void *target=dlsym(loaded?loaded:RTLD_DEFAULT,gate_symbol);
 if(!target)dl_iterate_phdr(find_gate,&target);
 int result=-3;struct target_check check={target,0};
 if(target)dl_iterate_phdr(validate_target,&check);
 if(check.executable){
  int status=hook_func(target,(void *)keep_hdr,(void **)&original_flags);
  if(status==0&&original_flags){atomic_store(&installed,1);result=1;Dl_info owner={0};dladdr(target,&owner);LOG("Native HDR gate resolved by ABI symbol in %s",owner.dli_fname?owner.dli_fname:"loaded ELF");}
  else{result=-5;LOG("Native hook failed result=%d",status);}
 }
 atomic_flag_clear(&resolving);return result;
}
static void library_loaded(const char *name,void *handle){if(handle&&atomic_load(&gate_requested)&&!atomic_load(&installed))install_gate(handle);}
__attribute__((visibility("default"),used)) LoadedFn native_init(const NativeAPIEntries *api){
 if(api&&api->hook_func){hook_func=api->hook_func;LOG("LSPosed native API ready version=%u",api->version);}return library_loaded;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *unused){LOG("HDR native library loaded");return JNI_VERSION_1_6;}
JNIEXPORT jint JNICALL Java_dev_local_miuihdr_NativeBridge_installGate(JNIEnv *env,jclass clazz){atomic_store(&gate_requested,1);return install_gate(NULL);}
JNIEXPORT jint JNICALL Java_dev_local_miuihdr_NativeBridge_firstSignal(JNIEnv *env,jclass clazz,jobject bitmap){
 AndroidBitmapInfo info;void *pixels=NULL;
 if(AndroidBitmap_getInfo(env,bitmap,&info)!=0)return -1;
 if(info.flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE)return -1;
 if(info.format!=ANDROID_BITMAP_FORMAT_A_8&&info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)return -1;
 if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=0||!pixels)return -1;
 int peak=0;const unsigned char *data=pixels;
 for(uint32_t y=0;y<info.height&&peak<=1;y++){
  const unsigned char *row=data+(size_t)y*info.stride;
  for(uint32_t x=0;x<info.width;x++){
   int value;
   if(info.format==ANDROID_BITMAP_FORMAT_A_8)value=row[x];
   else{const unsigned char *p=row+4*x;value=p[0]>p[1]?p[0]:p[1];if(p[2]>value)value=p[2];if((info.flags&ANDROID_BITMAP_FLAGS_ALPHA_MASK)==ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL)value=value*p[3]/255;}
   if(value>peak)peak=value;if(peak>1)break;
  }
 }
 AndroidBitmap_unlockPixels(env,bitmap);return peak;
}

/* Inspect stored gain values, not color-managed getPixel() output. A three-
 * component bitmap can still contain identical R/G/B at every pixel. */
JNIEXPORT jboolean JNICALL Java_dev_local_miuihdr_NativeBridge_gainmapChannels(JNIEnv *env,jclass clazz,jobject bitmap,jintArray report){
 AndroidBitmapInfo info;void *pixels=NULL;jint values[3]={0,0,0};
 if(!bitmap||!report||(*env)->GetArrayLength(env,report)!=3||AndroidBitmap_getInfo(env,bitmap,&info)!=0)return JNI_FALSE;
 if(info.flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE)return JNI_FALSE;
 if(!info.width||!info.height||(uint64_t)info.width*info.height>16000000)return JNI_FALSE;
 if(info.format!=ANDROID_BITMAP_FORMAT_A_8&&info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)return JNI_FALSE;
 values[0]=info.format==ANDROID_BITMAP_FORMAT_A_8?1:3;
 values[1]=(jint)(info.width*info.height);
 if(values[0]==3){
  if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=0||!pixels)return JNI_FALSE;
  for(uint32_t y=0;y<info.height;y++){
   const unsigned char *row=(const unsigned char *)pixels+(size_t)y*info.stride;
   for(uint32_t x=0;x<info.width;x++){
    const unsigned char *p=row+4*x;
    if(p[0]!=p[1]||p[1]!=p[2])values[2]++;
   }
  }
  AndroidBitmap_unlockPixels(env,bitmap);
 }
 (*env)->SetIntArrayRegion(env,report,0,3,values);return JNI_TRUE;
}

#include <stdlib.h>
#include <math.h>
/* All images have exactly the same shell geometry. Raw bytes avoid getPixels'
 * implicit conversion/clamping of Display P3 into sRGB. */
JNIEXPORT jboolean JNICALL Java_dev_local_miuihdr_NativeBridge_composeShellGainmap(JNIEnv *env,jclass clazz,jobject base,jobject gain,jobject shell,jobject result,jobject output,jfloatArray params,jfloatArray transfer){
 jobject objects[5]={base,gain,shell,result,output};AndroidBitmapInfo info[5];unsigned char *pixels[5]={0};int locked=0;float *ratios=NULL;jboolean ok=JNI_FALSE;
 if((*env)->GetArrayLength(env,params)!=21||(*env)->GetArrayLength(env,transfer)!=256)return JNI_FALSE;
 float m[21],linear[256];(*env)->GetFloatArrayRegion(env,params,0,21,m);(*env)->GetFloatArrayRegion(env,transfer,0,256,linear);
 for(int i=0;i<5;i++){
  if(AndroidBitmap_getInfo(env,objects[i],&info[i])||info[i].format!=ANDROID_BITMAP_FORMAT_RGBA_8888||(info[i].flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE))goto done;
  if(i&&(info[i].width!=info[0].width||info[i].height!=info[0].height))goto done;
  if(AndroidBitmap_lockPixels(env,objects[i],(void **)&pixels[i])||!pixels[i])goto done;locked++;
 }
 size_t count=(size_t)info[0].width*info[0].height;if(count>16000000)goto done;
 ratios=malloc(count*3*sizeof(float));if(!ratios)goto done;
 float gain_lut[3][256],minimum[3],maximum[3];const float eps=1.f/64;
 for(int c=0;c<3;c++){
  if(!(m[c]>0&&m[3+c]>0&&m[6+c]>0))goto done;
  minimum[c]=fminf(1,m[c]);maximum[c]=fmaxf(1.0001f,m[3+c]);
  for(int v=0;v<256;v++)gain_lut[c][v]=expf(logf(m[c])+(logf(m[3+c])-logf(m[c]))*powf(v/255.f,m[6+c]));
 }
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++){
  const unsigned char *b=pixels[0]+y*info[0].stride+4*x,*g=pixels[1]+y*info[1].stride+4*x,*s=pixels[2]+y*info[2].stride+4*x,*r=pixels[3]+y*info[3].stride+4*x;
  float weight=r[3]?fminf(1,(b[3]/255.f)*(1-s[3]/255.f)/(r[3]/255.f)):0;
  for(int c=0;c<3;c++){
   int bv=b[3]?((int)b[c]*255+b[3]/2)/b[3]:0,rv=r[3]?((int)r[c]*255+r[3]/2)/r[3]:0;
   if(bv>255)bv=255;if(rv>255)rv=255;
   float B=linear[bv],R=linear[rv],H=(B+m[9+c])*gain_lut[c][g[c]]-m[12+c];
   float target=fmaxf(0,R+weight*(H-B)),ratio=(target+eps)/(R+eps);
   if(!isfinite(ratio)||ratio<=0)goto done;
   ratios[((size_t)y*info[0].width+x)*3+c]=ratio;
   minimum[c]=fminf(minimum[c],ratio);maximum[c]=fmaxf(maximum[c],ratio);
  }
 }
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++){
  unsigned char *p=pixels[4]+y*info[4].stride+4*x;
  for(int c=0;c<3;c++){
   float ratio=ratios[((size_t)y*info[0].width+x)*3+c];
   float encoded=(logf(ratio)-logf(minimum[c]))/(logf(maximum[c])-logf(minimum[c]));
   p[c]=(unsigned char)lroundf(fmaxf(0,fminf(1,encoded))*255);
  }p[3]=255;
 }
 for(int c=0;c<3;c++){m[15+c]=minimum[c];m[18+c]=maximum[c];}
 (*env)->SetFloatArrayRegion(env,params,0,21,m);ok=JNI_TRUE;
 done:free(ratios);for(int i=locked-1;i>=0;i--)AndroidBitmap_unlockPixels(env,objects[i]);return ok;
}

#include "gainmap_precision.c"
#include "jpeg444.c"

#include "hdr_capture.c"
