#ifndef HDR_RGB_PATCH_H
#define HDR_RGB_PATCH_H
#include "sha256.h"
#include "rgb_shader_bytes.h"
/* The private shader/metadata ABI is audited only for this complete library.
 * Normalize our two independent modifications before checking the fingerprint.
 * Unknown builds retain the existing generic clamp patch, with an explicit
 * unsupported RGB diagnostic. No offset guessing or private ABI extrapolation. */
typedef struct {size_t offset,length,clamp;const char *stock_sha256;} HdrRgbProfile;
static const HdrRgbProfile hdr_rgb_profile={0x164682,711,0x539fe8,
 "43598f06ca2c3c96b9eb243e4acb177a5ee77c01794d1cbac92c3934ae32c40e"};
static void hdr_rgb_bytes(unsigned char *out,size_t n){memset(out,' ',n);memcpy(out,hdr_rgb_shader,sizeof(hdr_rgb_shader)-1);}
/* Return 1 = scalar, 2 = RGB, 0 = unsupported. Caller has validated ELF header.
 * Kept parameterized for synthetic host tests; production uses only the above
 * immutable profile. Inspection never changes even the input memory. */
static int hdr_rgb_inspect(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,
 const HdrRgbProfile *p,size_t clamp,uint32_t old_word,uint32_t new_word){
 if(p->length!=sizeof(hdr_rgb_original)-1||sizeof(hdr_rgb_shader)-1>p->length||
    clamp!=p->clamp||p->offset>size||p->length>=size-p->offset||
    p->clamp<p->offset+p->length+1||p->clamp>size||size-p->clamp<4||
    data[p->offset+p->length]!=0||old_word!=UINT32_C(0x360001a0)||new_word!=UINT32_C(0x1400000d))return 0;
 uint32_t word;memcpy(&word,data+p->clamp,4);if(word!=old_word&&word!=new_word)return 0;
 unsigned char rgb[sizeof(hdr_rgb_original)-1];hdr_rgb_bytes(rgb,sizeof(rgb));
 int state=!memcmp(data+p->offset,hdr_rgb_original,p->length)?1:!memcmp(data+p->offset,rgb,p->length)?2:0;if(!state)return 0;
 unsigned maps=0;
 for(unsigned i=0;i<eh->e_phnum;i++){
  Elf64_Phdr ph;memcpy(&ph,data+eh->e_phoff+i*sizeof(ph),sizeof(ph));
  if(ph.p_type!=PT_LOAD)continue;
  if(ph.p_offset>size||ph.p_filesz>size-ph.p_offset)return 0;
  if(ph.p_offset<p->offset+p->length+1&&ph.p_offset+ph.p_filesz>p->offset){
   if(ph.p_flags!=PF_R||ph.p_offset>p->offset||ph.p_filesz<p->offset+p->length+1-ph.p_offset)return 0;maps++;
  }
 }
 if(maps!=1)return 0;
 HdrSha256 sha;char hex[65];hdr_sha_init(&sha);
 hdr_sha_update(&sha,data,p->offset);hdr_sha_update(&sha,hdr_rgb_original,p->length);
 hdr_sha_update(&sha,data+p->offset+p->length,p->clamp-p->offset-p->length);
 unsigned char original_word[4]={0xa0,0x01,0x00,0x36};hdr_sha_update(&sha,original_word,4);
 hdr_sha_update(&sha,data+p->clamp+4,size-p->clamp-4);hdr_sha_final(&sha,hex);
 return strcmp(hex,p->stock_sha256)?0:state;
}
#endif
