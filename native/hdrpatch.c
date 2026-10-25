/* Reproduce the supplied module's clamp patch in the device's own ELF.
 * No device/model/ROM allowlist. A missing/ambiguous instruction sequence is
 * an error, and no output is committed in that case. */
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "elf_match.h"

static const unsigned char prefix[]={0x08,0x00,0x40,0xf9,0x08,0x39,0x42,0xf9,0x00,0x01,0x3f,0xd6};
static const unsigned char suffix[]={0x01,0x10,0x2e,0x1e,0xa0,0x46,0x41,0xbd,0x00,0x20,0x21,0x1e,0x2d,0x01,0x00,0x54,0xa8,0xe2,0x45,0x39,0xe8,0x00,0x00,0x37,0xa1,0x42,0x41,0xbd,0x20,0x20,0x20,0x1e,0x20,0x4c,0x20,0x1e,0x02,0x00,0x00,0x14,0xa0,0x46,0x41,0xbd,0xa0,0x42,0x01,0xbd};
static const unsigned char original[]={0xa0,0x01,0x00,0x36},patched[]={0x0d,0x00,0x00,0x14};
static int fail(const char *s){fprintf(stderr,"PATCH_ERROR: %s\n",s);return 1;}
int main(int argc,char **argv){
 int probe=argc==3&&!strcmp(argv[1],"probe");
 if(!probe&&(argc!=4||(strcmp(argv[1],"patch")&&strcmp(argv[1],"restore"))))return fail("usage: hdrpatch probe input | patch|restore input output");
 FILE *in=fopen(argv[2],"rb");if(!in)return fail("cannot read input");
 if(fseek(in,0,SEEK_END)){fclose(in);return fail("seek failed");}
 long length=ftell(in);rewind(in);if(length<(long)sizeof(Elf64_Ehdr)||length>128*1024*1024){fclose(in);return fail("invalid ELF size");}
 size_t size=(size_t)length;unsigned char *data=malloc(size);if(!data){fclose(in);return fail("out of memory");}
 if(fread(data,1,size,in)!=size){fclose(in);free(data);return fail("short read");}fclose(in);
 Elf64_Ehdr eh;memcpy(&eh,data,sizeof(eh));
 if(memcmp(eh.e_ident,ELFMAG,SELFMAG)){free(data);return fail("malformed ELF header");}
 if(eh.e_ident[EI_CLASS]!=ELFCLASS64||eh.e_ident[EI_DATA]!=ELFDATA2LSB||eh.e_machine!=EM_AARCH64){free(data);return fail("unsupported ELF architecture (requires ELF64 AArch64); original retained");}
 if(eh.e_type!=ET_DYN||eh.e_phentsize!=sizeof(Elf64_Phdr)||eh.e_phoff>size||(uint64_t)eh.e_phnum*sizeof(Elf64_Phdr)>size-eh.e_phoff){free(data);return fail("unsupported or malformed ELF");}
 size_t match=0,count=0;uint32_t restore_word=0,patch_word=0;const char *method="";
 for(unsigned n=0;n<eh.e_phnum;n++){
  Elf64_Phdr ph;memcpy(&ph,data+eh.e_phoff+n*sizeof(ph),sizeof(ph));
  if(ph.p_type!=PT_LOAD||!(ph.p_flags&PF_X))continue;
  if(ph.p_offset>size||ph.p_filesz>size-ph.p_offset){free(data);return fail("invalid executable segment");}
  size_t end=ph.p_offset+ph.p_filesz;
  for(size_t i=(ph.p_offset+3)&~(size_t)3;i+sizeof(prefix)+4+sizeof(suffix)<=end;i+=4){
   uint32_t old_word,new_word;size_t used=0;
   int exact=!memcmp(data+i,prefix,sizeof(prefix))&&!memcmp(data+i+sizeof(prefix)+4,suffix,sizeof(suffix))&&(!memcmp(data+i+sizeof(prefix),original,4)||!memcmp(data+i+sizeof(prefix),patched,4));
   int semantic=semantic_clamp(data+i,end-i,&old_word,&new_word,&used)&&in_capture_function(data,size,&eh,i,used);
   if(exact||semantic){
    size_t candidate=i+sizeof(prefix);if(count&&candidate==match)continue;
    match=candidate;count++;restore_word=semantic?old_word:insn(original);patch_word=semantic?new_word:insn(patched);method=semantic?"symbol-dataflow":"legacy-exact";
   }
  }
 }
 if(count!=1){free(data);return fail(count?"ambiguous clamp sequence; original retained":"capture implementation not recognized (symbol/dataflow and legacy fingerprint); original retained");}
 if(probe){printf("PATCH_MATCH offset=0x%zx architecture=ELF64_AARCH64 method=%s state=%s\n",match,method,insn(data+match)==patch_word?"patched":"original");free(data);return 0;}
 int wasPatched=insn(data+match)==patch_word;uint32_t output_word=!strcmp(argv[1],"patch")?patch_word:restore_word;memcpy(data+match,&output_word,4);
 char temp[4096];if(snprintf(temp,sizeof(temp),"%s.tmp.%ld",argv[3],(long)getpid())>=(int)sizeof(temp)){free(data);return fail("output path too long");}
 int fd=open(temp,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);if(fd<0){free(data);return fail("cannot create output");}
 size_t done=0;while(done<size){ssize_t n=write(fd,data+done,size-done);if(n<0&&errno==EINTR)continue;if(n<=0)break;done+=(size_t)n;}
 int ok=done==size&&!fsync(fd);if(close(fd))ok=0;free(data);
 if(!ok||rename(temp,argv[3])){unlink(temp);return fail("output commit failed");}
 printf("PATCH_OK offset=0x%zx input=%s operation=%s\n",match,wasPatched?"patched":"original",argv[1]);return 0;
}
