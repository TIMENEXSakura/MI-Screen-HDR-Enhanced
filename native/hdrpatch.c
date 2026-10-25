/* Reproduce the supplied module's clamp patch in the device's own ELF.
 * No device/model/ROM allowlist. A missing/ambiguous instruction sequence is
 * an error, and no output is committed in that case. */
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
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
static int fail(const char *s){
 static const struct {const char *reason,*description;} reasons[]={
  {"cannot read input","无法打开输入库；请检查路径、读取权限及文件是否存在。"},
  {"seek failed","无法定位输入库末尾，不能可靠读取文件长度。"},
  {"invalid ELF size","输入库大小异常：文件需至少包含 64 字节 ELF 头，且不得超过 128 MiB。"},
  {"out of memory","内存分配失败，无法读取完整运行库。"},
  {"short read","读取到的字节数少于文件声明长度，输入库可能发生变化或读取失败。"},
  {"malformed ELF header","ELF 文件头标识损坏，输入不是可识别的 ELF 运行库。"},
  {"unsupported ELF architecture (requires ELF64 AArch64); original retained","仅支持小端 64 位 ARM64（AArch64）库；32 位 ARM、其他架构及大端格式不支持，保留原库。"},
  {"unsupported or malformed ELF","输入不是受支持的 ET_DYN 共享库，或 ELF 程序头大小、数量、位置已损坏。"},
  {"invalid executable segment","可执行代码段的文件偏移或长度超出库文件边界，不能安全定位指令。"},
  {"ambiguous clamp sequence; original retained","存在多个满足条件的亮度截断实现，无法确定唯一修补位置，保留原库。"},
  {"capture implementation not recognized (symbol/dataflow and legacy fingerprint); original retained","没有识别到兼容的截屏亮度截断实现；函数符号、指令数据流及精确特征码检查均未得到有效匹配，保留原库。"},
  {"output path too long","输出路径过长，无法创建安全的临时副本。"},
  {"cannot create output","无法创建临时输出文件；请检查目标目录、写入权限与可用存储空间。"},
  {"output commit failed","输出副本的写入、存储同步、关闭或提交失败；具体失败阶段见上方日志，已尝试清理临时副本。"}
 };
 const char *description="操作未完成，具体失败原因见下方诊断。";
 for(size_t i=0;i<sizeof(reasons)/sizeof(reasons[0]);i++)if(!strcmp(s,reasons[i].reason)){description=reasons[i].description;break;}
 if(!strncmp(s,"usage:",6))description="参数错误：使用 probe 只读检查，或 patch/restore 指定输入库和输出文件。";
 fprintf(stderr,"修补中止：%s\nPATCH_ERROR: %s\n",description,s);return 1;
}
/* Diagnostic values are single-line, percent-escaped tokens. Never let a file
 * name or ELF symbol forge another event in the install log. */
static void log_value(const char *s){
 size_t n=0;for(;s[n]&&n<1024;n++){
  unsigned char c=(unsigned char)s[n];
  if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||strchr("_./:+-",c))putchar(c);
  else printf("%%%02X",c);
 }
 if(s[n])printf("...[truncated]");
}
static void log_u64_end(const char *key,uint64_t start,uint64_t length){
 if(length<=UINT64_MAX-start)printf(" %s=0x%" PRIx64,key,start+length);
 else printf(" %s=unavailable",key);
}
static int file_address(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,size_t offset,uint64_t *address){
 for(unsigned n=0;n<eh->e_phnum;n++){
  Elf64_Phdr ph;memcpy(&ph,data+eh->e_phoff+n*sizeof(ph),sizeof(ph));
  if(ph.p_type!=PT_LOAD||!(ph.p_flags&PF_X)||!span(ph.p_offset,ph.p_filesz,size)||offset<ph.p_offset||offset-ph.p_offset>ph.p_filesz)continue;
  uint64_t delta=offset-ph.p_offset;if(delta>UINT64_MAX-ph.p_vaddr)continue;
  *address=ph.p_vaddr+delta;return 1;
 }return 0;
}
static void log_address(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,size_t offset,const char *key){
 uint64_t address;if(file_address(data,size,eh,offset,&address))printf(" %s=0x%" PRIx64,key,address);
 else printf(" %s=unavailable",key);
}
static int log_note_build_id(const unsigned char *data,size_t size,uint64_t offset,uint64_t length){
 if(!span(offset,length,size))return 0;
 uint64_t cursor=offset,end=offset+length;
 while(end-cursor>=sizeof(Elf64_Nhdr)){
  Elf64_Nhdr note;memcpy(&note,data+cursor,sizeof(note));cursor+=sizeof(note);
  uint64_t name_bytes=((uint64_t)note.n_namesz+3)&~UINT64_C(3),desc_bytes=((uint64_t)note.n_descsz+3)&~UINT64_C(3);
  if(name_bytes>end-cursor||desc_bytes>end-cursor-name_bytes)return 0;
  if(note.n_type==NT_GNU_BUILD_ID&&note.n_namesz==4&&!memcmp(data+cursor,"GNU\0",4)&&note.n_descsz){
   printf("ELF_BUILD_ID value=");
   uint32_t shown=note.n_descsz>64?64:note.n_descsz;
   for(uint32_t i=0;i<shown;i++)printf("%02x",data[cursor+name_bytes+i]);
   printf(" bytes=%u truncated=%s\n",note.n_descsz,shown<note.n_descsz?"true":"false");return 1;
  }
  cursor+=name_bytes+desc_bytes;
 }return 0;
}
static void log_build_id(const unsigned char *data,size_t size,const Elf64_Ehdr *eh){
 puts("构建标识：GNU Build-ID 用于区分库的构建版本；缺失或损坏时记为 unavailable，不凭它认定修补成功。");
 for(unsigned n=0;n<eh->e_phnum;n++){
  Elf64_Phdr ph;memcpy(&ph,data+eh->e_phoff+n*sizeof(ph),sizeof(ph));
  if(ph.p_type==PT_NOTE&&log_note_build_id(data,size,ph.p_offset,ph.p_filesz))return;
 }
 if(eh->e_shentsize==sizeof(Elf64_Shdr)&&span(eh->e_shoff,(uint64_t)eh->e_shnum*sizeof(Elf64_Shdr),size)){
  for(unsigned n=0;n<eh->e_shnum;n++){
   Elf64_Shdr sh;memcpy(&sh,data+eh->e_shoff+n*sizeof(sh),sizeof(sh));
   if(sh.sh_type==SHT_NOTE&&log_note_build_id(data,size,sh.sh_offset,sh.sh_size))return;
  }
 }
 puts("ELF_BUILD_ID status=unavailable reason=missing_or_malformed_note");
}
static void log_symbol(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,size_t match){
 FunctionSymbol symbol;
 if(!function_symbol(data,size,eh,match,4,0,&symbol)){puts("函数信息：没有可用的包含此位置的函数符号，不能给出可信函数名；本次匹配方法见 method。");puts("ELF_SYMBOL status=unavailable reason=no_usable_function_symbol");return;}
 puts("函数信息：name 为库内原始符号名；file_offset/file_end 是函数在文件中的起止偏移，elf_va/elf_va_end 是其 ELF 虚拟地址范围（末端不含）。");
 printf("ELF_SYMBOL status=matched name=");log_value(symbol.name);
 printf(" file_offset=0x%zx file_end=0x%" PRIx64 " elf_va=0x%" PRIx64,symbol.offset,symbol.offset+symbol.length,symbol.address);
 log_u64_end("elf_va_end",symbol.address,symbol.length);putchar('\n');
}
int main(int argc,char **argv){
 setvbuf(stdout,NULL,_IOLBF,0);
 int probe=argc==3&&!strcmp(argv[1],"probe");
 if(!probe&&(argc!=4||(strcmp(argv[1],"patch")&&strcmp(argv[1],"restore"))))return fail("usage: hdrpatch probe input | patch|restore input output");
 printf("ELF_INPUT path=");log_value(argv[2]);printf(" operation=%s address_space=file_and_elf_virtual\n",argv[1]);
 FILE *in=fopen(argv[2],"rb");if(!in)return fail("cannot read input");
 if(fseek(in,0,SEEK_END)){fclose(in);return fail("seek failed");}
 long length=ftell(in);rewind(in);if(length<(long)sizeof(Elf64_Ehdr)||length>128*1024*1024){fclose(in);return fail("invalid ELF size");}
 size_t size=(size_t)length;unsigned char *data=malloc(size);if(!data){fclose(in);return fail("out of memory");}
 if(fread(data,1,size,in)!=size){fclose(in);free(data);return fail("short read");}fclose(in);
 Elf64_Ehdr eh;memcpy(&eh,data,sizeof(eh));
 if(memcmp(eh.e_ident,ELFMAG,SELFMAG)){free(data);return fail("malformed ELF header");}
 /* e_type/e_machine are at the same location in ELF32/64. Decode explicitly
  * so even an unsupported big-endian input gets an accurate diagnostic. */
 int big=eh.e_ident[EI_DATA]==ELFDATA2MSB;
 unsigned type=big?((unsigned)data[16]<<8)|data[17]:data[16]|((unsigned)data[17]<<8);
 unsigned machine=big?((unsigned)data[18]<<8)|data[19]:data[18]|((unsigned)data[19]<<8);
 puts("库信息：class=位数，endian=字节序，machine=指令架构，type=ELF 类型，size=文件大小（字节）。");
 printf("ELF_INFO class=%s endian=%s machine=%s machine_id=%u type=%s type_id=%u size=%zu\n",
  eh.e_ident[EI_CLASS]==ELFCLASS64?"64":eh.e_ident[EI_CLASS]==ELFCLASS32?"32":"unknown",
  big?"big":eh.e_ident[EI_DATA]==ELFDATA2LSB?"little":"unknown",
  machine==EM_AARCH64?"AARCH64":machine==EM_ARM?"ARM":machine==EM_X86_64?"X86_64":machine==EM_386?"X86":"other",machine,
  type==ET_DYN?"ET_DYN":type==ET_EXEC?"ET_EXEC":type==ET_REL?"ET_REL":"other",type,size);
 if(eh.e_ident[EI_CLASS]!=ELFCLASS64||eh.e_ident[EI_DATA]!=ELFDATA2LSB||eh.e_machine!=EM_AARCH64){free(data);return fail("unsupported ELF architecture (requires ELF64 AArch64); original retained");}
 if(eh.e_type!=ET_DYN||eh.e_phentsize!=sizeof(Elf64_Phdr)||eh.e_phoff>size||(uint64_t)eh.e_phnum*sizeof(Elf64_Phdr)>size-eh.e_phoff){free(data);return fail("unsupported or malformed ELF");}
 log_build_id(data,size,&eh);
 puts("正在定位：检查原有精确特征码及截屏函数内的指令数据流，只接受唯一匹配；legacy-exact=精确特征码，symbol-dataflow=函数符号与指令数据流。");
 puts("代码区域：仅扫描可执行 PT_LOAD 段。file_offset/file_end=文件偏移范围；elf_va/elf_va_end=ELF 虚拟地址范围，elf_mem_end=内存段末端；这些不是进程运行地址。范围末端不含。");
 puts("PATCH_STAGE phase=scan status=started checks=legacy_fingerprint_and_capture_symbol_dataflow");
 size_t match=0,count=0;uint32_t restore_word=0,patch_word=0;const char *method="";
 for(unsigned n=0;n<eh.e_phnum;n++){
  Elf64_Phdr ph;memcpy(&ph,data+eh.e_phoff+n*sizeof(ph),sizeof(ph));
  if(ph.p_type!=PT_LOAD||!(ph.p_flags&PF_X))continue;
  if(ph.p_offset>size||ph.p_filesz>size-ph.p_offset){free(data);return fail("invalid executable segment");}
  printf("ELF_SEGMENT index=%u file_offset=0x%" PRIx64 " file_end=0x%" PRIx64 " elf_va=0x%" PRIx64,n,ph.p_offset,ph.p_offset+ph.p_filesz,ph.p_vaddr);
  log_u64_end("elf_va_end",ph.p_vaddr,ph.p_filesz);log_u64_end("elf_mem_end",ph.p_vaddr,ph.p_memsz);
  printf(" flags=%c%cX\n",ph.p_flags&PF_R?'R':'-',ph.p_flags&PF_W?'W':'-');
  size_t end=ph.p_offset+ph.p_filesz;
  for(size_t i=(ph.p_offset+3)&~(size_t)3;i+sizeof(prefix)+4+sizeof(suffix)<=end;i+=4){
   uint32_t old_word,new_word;size_t used=0;
   int exact=!memcmp(data+i,prefix,sizeof(prefix))&&!memcmp(data+i+sizeof(prefix)+4,suffix,sizeof(suffix))&&(!memcmp(data+i+sizeof(prefix),original,4)||!memcmp(data+i+sizeof(prefix),patched,4));
   int semantic=semantic_clamp(data+i,end-i,&old_word,&new_word,&used)&&in_capture_function(data,size,&eh,i,used);
   if(exact||semantic){
    size_t candidate=i+sizeof(prefix);if(count&&candidate==match)continue;
    match=candidate;count++;restore_word=semantic?old_word:insn(original);patch_word=semantic?new_word:insn(patched);method=semantic?"symbol-dataflow":"legacy-exact";
    if(count<=32){printf("PATCH_CANDIDATE index=%zu method=%s file_offset=0x%zx",count,method,match);log_address(data,size,&eh,match,"elf_va");putchar('\n');}
   }
  }
 }
 printf("PATCH_SCAN candidates=%zu decision=%s reason=%s candidates_reported=%zu\n",count,count==1?"unique":"reject",count==1?"unique_validated_sequence":count?"ambiguous_clamp_sequence":"unrecognized_capture_implementation",count>32?32:count);
 if(count!=1){printf("定位失败：找到 %zu 个候选，%s；停止修补并保留原库。\n",count,count?"存在歧义，不能确定唯一位置":"没有同时满足安全校验的实现");free(data);return fail(count?"ambiguous clamp sequence; original retained":"capture implementation not recognized (symbol/dataflow and legacy fingerprint); original retained");}
 puts("定位完成：仅有一个通过校验的候选，下面给出所属函数和修改位置。");
 log_symbol(data,size,&eh,match);
 size_t target=match+(size_t)(signed_bits(patch_word&0x3ffffff,26)*4);
 puts("补丁详情：file_offset=修改位置的文件偏移，elf_va=对应 ELF 虚拟地址；current_word=当前机器码，original_word=还原机器码，patched_word=修补机器码，均为 32 位指令。branch_target 是跳转目标。");
 puts("修补含义：将按 W0 第 0 位判断的 TBZ 条件跳转改为 B 无条件跳转，跳过截屏亮度截断逻辑；不改变整幅图的比例，也不将 ELF 虚拟地址冒充运行地址。");
 printf("PATCH_DETAIL file_offset=0x%zx",match);log_address(data,size,&eh,match,"elf_va");
 printf(" current_word=0x%08" PRIx32 " original_word=0x%08" PRIx32 " patched_word=0x%08" PRIx32 " branch_target_file_offset=0x%zx",insn(data+match),restore_word,patch_word,target);
 log_address(data,size,&eh,target,"branch_target_elf_va");
 puts(" original_instruction=TBZ_W0_bit0 patched_instruction=B effect=skip_capture_brightness_clamp runtime_address=not_resolved");
 if(probe){printf("PATCH_MATCH offset=0x%zx architecture=ELF64_AARCH64 method=%s state=%s\n",match,method,insn(data+match)==patch_word?"patched":"original");free(data);return 0;}
 int wasPatched=insn(data+match)==patch_word;uint32_t output_word=!strcmp(argv[1],"patch")?patch_word:restore_word;
 printf("准备%s：目标机器码为 0x%08" PRIx32 "，只在输出副本中替换已校验位置的 4 字节。\n",!strcmp(argv[1],"patch")?"修补":"还原",output_word);
 printf("PATCH_STAGE operation=%s phase=prepare status=started target_word=0x%08" PRIx32 " effect=%s\n",argv[1],output_word,!strcmp(argv[1],"patch")?"skip_capture_brightness_clamp":"restore_capture_brightness_clamp");
 memcpy(data+match,&output_word,4);
 printf("PATCH_STAGE operation=%s phase=prepare status=complete\n",argv[1]);
 char temp[4096];if(snprintf(temp,sizeof(temp),"%s.tmp.%ld",argv[3],(long)getpid())>=(int)sizeof(temp)){free(data);return fail("output path too long");}
 int fd=open(temp,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);if(fd<0){free(data);return fail("cannot create output");}
 printf("PATCH_OUTPUT path=");log_value(argv[3]);puts(" role=generated_copy");
 puts("正在写入：将库内容与目标机器码写入临时副本，bytes/total 显示已写入与总字节数。");
 printf("PATCH_STAGE operation=%s phase=write status=started bytes=%zu\n",argv[1],size);
 size_t done=0;while(done<size){ssize_t n=write(fd,data+done,size-done);if(n<0&&errno==EINTR)continue;if(n<=0)break;done+=(size_t)n;}
 printf("PATCH_STAGE operation=%s phase=write status=%s bytes=%zu total=%zu\n",argv[1],done==size?"complete":"failed",done,size);
 int ok=done==size;
 if(ok){
  puts("正在同步：调用 fsync，等待临时副本的数据完成存储同步。");
  printf("PATCH_STAGE operation=%s phase=fsync status=started\n",argv[1]);ok=!fsync(fd);
  printf("PATCH_STAGE operation=%s phase=fsync status=%s\n",argv[1],ok?"complete":"failed");
 }
 if(close(fd))ok=0;free(data);
 if(ok){puts("正在提交：关闭写入文件后，使用原子重命名提交生成的库副本。");printf("PATCH_STAGE operation=%s phase=commit status=started\n",argv[1]);ok=!rename(temp,argv[3]);}
 if(!ok){puts("提交失败：写入、同步、关闭或重命名未成功，移除临时副本，不报告修补成功。");printf("PATCH_STAGE operation=%s phase=commit status=failed\n",argv[1]);unlink(temp);return fail("output commit failed");}
 printf("PATCH_STAGE operation=%s phase=commit status=complete\n",argv[1]);
 puts("副本已生成：这仅表示库副本写入完成，安装校验、挂载和是否生效由后续步骤确认。");
 printf("PATCH_OK offset=0x%zx input=%s operation=%s\n",match,wasPatched?"patched":"original",argv[1]);return 0;
}
