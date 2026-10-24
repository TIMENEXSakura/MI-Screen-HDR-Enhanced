/* Independent JPEG encoder: full-resolution chroma in base and gain map. */
#include <jpeglib.h>
#include <setjmp.h>
#include <limits.h>
struct hdr_jpeg_error { struct jpeg_error_mgr pub; jmp_buf jump; };
static void hdr_jpeg_fail(j_common_ptr jpeg) {
 struct hdr_jpeg_error *err=(struct hdr_jpeg_error *)jpeg->err;longjmp(err->jump,1);
}
JNIEXPORT jbyteArray JNICALL Java_dev_local_miuihdr_NativeBridge_encodeJpeg444(JNIEnv *env,jclass clazz,jobject bitmap,jboolean gainmap){
 AndroidBitmapInfo info;void *raw=NULL;
 if(AndroidBitmap_getInfo(env,bitmap,&info)||(info.flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE))return NULL;
 if(info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888&&info.format!=ANDROID_BITMAP_FORMAT_A_8)return NULL;
 if(!info.width||!info.height||(uint64_t)info.width*info.height>16000000)return NULL;
 if(AndroidBitmap_lockPixels(env,bitmap,&raw)||!raw)return NULL;
 if(!gainmap&&info.format==ANDROID_BITMAP_FORMAT_RGBA_8888){
  for(uint32_t y=0;y<info.height;y++)for(uint32_t x=0;x<info.width;x++)
   if(((unsigned char *)raw)[(size_t)y*info.stride+x*4+3]!=255){AndroidBitmap_unlockPixels(env,bitmap);return NULL;}
 }
 /* Keep longjmp cleanup state in heap storage (modified stack locals are indeterminate). */
 struct jpeg_compress_struct *jpeg=calloc(1,sizeof(*jpeg));
 struct hdr_jpeg_error err;unsigned char **memory=calloc(1,sizeof(*memory));unsigned long *size=calloc(1,sizeof(*size));jbyteArray answer=NULL;
 if(!jpeg||!memory||!size)goto done;
 jpeg->err=jpeg_std_error(&err.pub);err.pub.error_exit=hdr_jpeg_fail;
 if(setjmp(err.jump))goto done;
 jpeg_create_compress(jpeg);jpeg_mem_dest(jpeg,memory,size);
 jpeg->image_width=info.width;jpeg->image_height=info.height;
 jpeg->input_components=info.format==ANDROID_BITMAP_FORMAT_A_8?1:4;
 jpeg->in_color_space=info.format==ANDROID_BITMAP_FORMAT_A_8?JCS_GRAYSCALE:JCS_EXT_RGBA;
 jpeg_set_defaults(jpeg);jpeg_set_quality(jpeg,100,TRUE);jpeg->dct_method=JDCT_ISLOW;
 for(int i=0;i<jpeg->num_components;i++){jpeg->comp_info[i].h_samp_factor=1;jpeg->comp_info[i].v_samp_factor=1;}
 jpeg_start_compress(jpeg,TRUE);
 while(jpeg->next_scanline<jpeg->image_height){JSAMPROW row=(unsigned char *)raw+(size_t)jpeg->next_scanline*info.stride;jpeg_write_scanlines(jpeg,&row,1);}
 jpeg_finish_compress(jpeg);
 if(*size>INT_MAX||*size>64*1024*1024)goto done;
 answer=(*env)->NewByteArray(env,(jsize)*size);if(answer)(*env)->SetByteArrayRegion(env,answer,0,(jsize)*size,(jbyte *)*memory);
 done:if(jpeg&&jpeg->mem)jpeg_destroy_compress(jpeg);if(memory)free(*memory);free(memory);free(size);free(jpeg);AndroidBitmap_unlockPixels(env,bitmap);return answer;
}
