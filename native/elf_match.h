/* Bounds-checked ELF symbol ranges and AArch64 data-flow matcher. No firmware
 * addresses, file hashes, device names or vtable/structure offsets are assumed. */
static uint32_t insn(const unsigned char *p){uint32_t v;memcpy(&v,p,4);return v;}
static int64_t signed_bits(uint32_t n,unsigned bits){return (int64_t)((int32_t)(n<<(32-bits))>>(32-bits));}
static int branch_to(uint32_t w,size_t pc,size_t to){
 if((w&0xff000010)==0x54000000)return (int64_t)pc+signed_bits((w>>5)&0x7ffff,19)*4==(int64_t)to;
 if((w&0x7e000000)==0x36000000)return (int64_t)pc+signed_bits((w>>5)&0x3fff,14)*4==(int64_t)to;
 if((w&0xfc000000)==0x14000000)return (int64_t)pc+signed_bits(w&0x3ffffff,26)*4==(int64_t)to;
 return 0;
}
static unsigned rd(uint32_t w){return w&31;}
static unsigned rn(uint32_t w){return (w>>5)&31;}
static unsigned rm(uint32_t w){return (w>>16)&31;}
static int fp_load(uint32_t w){return (w&0xffc00000)==0xbd400000;}
static int fp_compare(uint32_t w,unsigned a,unsigned b){return w==(0x1e202000|(b<<16)|(a<<5));}
/* Capture's optional Xiaomi clamp: if white > 1 && !flag, brightness=min(brightness,white).
 * Validate the entire producer/compare/select/store chain, including all exits.
 * A matching float minimum elsewhere is insufficient. */
static int semantic_clamp(const unsigned char *p,size_t bytes,uint32_t *before,uint32_t *after,size_t *used){
 uint32_t w[16];size_t pc[16],cursor=0;
 for(int i=0;i<16;i++){
  do{if(cursor+4>bytes||cursor>=128)return 0;pc[i]=cursor;w[i]=insn(p+cursor);cursor+=4;}while(w[i]==0xd503201f); // scheduling/alignment NOP
 }
 if((w[0]&0xffc00000)!=0xf9400000||rn(w[0])!=0||((w[0]>>10)&4095)!=0)return 0;
 if((w[1]&0xffc00000)!=0xf9400000||rn(w[1])!=rd(w[0]))return 0;
 if(w[2]!=(0xd63f0000|(rd(w[1])<<5)))return 0;
 int tbz=(w[3]&0xfff8001f)==0x36000000;
 int patched=(w[3]&0xfc000000)==0x14000000;
 if((!tbz&&!patched)||!branch_to(w[3],pc[3],cursor))return 0;
 if((w[4]&~31u)!=0x1e2e1000||!fp_load(w[5]))return 0;
 unsigned white=rd(w[5]),one=rd(w[4]),base=rn(w[5]);
 if(white==one||!branch_to(w[7],pc[7],cursor))return 0;
 if(!((fp_compare(w[6],white,one)&&(w[7]&0xff00001f)==0x5400000d)||
      (fp_compare(w[6],one,white)&&(w[7]&0xff00001f)==0x5400000a)))return 0;
 if((w[8]&0xffc00000)!=0x39400000||rn(w[8])!=base)return 0;
 if((w[9]&0xfff8001f)!=(0x37000000|rd(w[8]))||!branch_to(w[9],pc[9],cursor))return 0;
 if(!fp_load(w[10])||rn(w[10])!=base||((w[10]^w[5])&0x003ffc00)==0)return 0;
 unsigned bright=rd(w[10]);if(bright==white)return 0;
 if((w[12]&0xffe00c00)!=0x1e200c00)return 0;
 unsigned cond=(w[12]>>12)&15;
 if(!((fp_compare(w[11],bright,white)&&rn(w[12])==bright&&rm(w[12])==white&&(cond==4||cond==11))||
      (fp_compare(w[11],white,bright)&&rn(w[12])==white&&rm(w[12])==bright&&(cond==4||cond==11))))return 0;
 if(!branch_to(w[13],pc[13],pc[15]))return 0;
 if(w[14]!=((w[5]&~31u)|rd(w[12])))return 0;
 if(w[15]!=((w[10]&~0x0040001fu)|rd(w[12])))return 0;
 uint32_t delta=(cursor-pc[3])/4;
 *before=0x36000000|(delta<<5);*after=0x14000000|delta;*used=cursor;
 // Caller patches the predicate branch, which follows the three call instructions.
 // NOPs before that branch are disallowed so the patch location stays unambiguous.
 return pc[3]==12;
}

static int span(uint64_t off,uint64_t length,size_t size){return off<=size&&length<=size-off;}
static int code_range(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,uint64_t addr,uint64_t length,size_t *offset){
 for(unsigned n=0;n<eh->e_phnum;n++){
  Elf64_Phdr ph;memcpy(&ph,data+eh->e_phoff+n*sizeof(ph),sizeof(ph));
  if(ph.p_type!=PT_LOAD||!(ph.p_flags&PF_X)||!span(ph.p_offset,ph.p_filesz,size))continue;
  if(addr>=ph.p_vaddr&&addr-ph.p_vaddr<=ph.p_filesz&&length<=ph.p_filesz-(addr-ph.p_vaddr)){
   *offset=ph.p_offset+(addr-ph.p_vaddr);return 1;
  }
 }return 0;
}
static int capture_symbol(const char *name){
 const char *prefix="_ZN7android14SurfaceFlinger16renderScreenImpl";
 return !strncmp(name,prefix,strlen(prefix));
}
/* Section symbols are retained in Android shared libraries even when the full
 * .symtab is stripped. Fully sectionless binaries use only the exact legacy
 * fingerprint; never extrapolate a loose pattern across unrelated functions. */
typedef struct {const char *name;uint64_t address,length;size_t offset;} FunctionSymbol;
static int function_symbol(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,size_t pos,size_t length,int capture_only,FunctionSymbol *result){
 if(eh->e_shentsize!=sizeof(Elf64_Shdr)||!span(eh->e_shoff,(uint64_t)eh->e_shnum*sizeof(Elf64_Shdr),size))return 0;
 for(unsigned n=0;n<eh->e_shnum;n++){
  Elf64_Shdr sh;memcpy(&sh,data+eh->e_shoff+n*sizeof(sh),sizeof(sh));
  if((sh.sh_type!=SHT_DYNSYM&&sh.sh_type!=SHT_SYMTAB)||sh.sh_entsize!=sizeof(Elf64_Sym)||sh.sh_link>=eh->e_shnum||!span(sh.sh_offset,sh.sh_size,size))continue;
  Elf64_Shdr strings;memcpy(&strings,data+eh->e_shoff+sh.sh_link*sizeof(strings),sizeof(strings));
  if(strings.sh_type!=SHT_STRTAB||!span(strings.sh_offset,strings.sh_size,size))continue;
  for(uint64_t i=0;i+sizeof(Elf64_Sym)<=sh.sh_size;i+=sizeof(Elf64_Sym)){
   Elf64_Sym sym;memcpy(&sym,data+sh.sh_offset+i,sizeof(sym));
   if(ELF64_ST_TYPE(sym.st_info)!=STT_FUNC||sym.st_shndx==SHN_UNDEF||sym.st_name>=strings.sh_size||!sym.st_size)continue;
   const char *name=(const char *)data+strings.sh_offset+sym.st_name;
   if(!memchr(name,0,strings.sh_size-sym.st_name)||(capture_only&&!capture_symbol(name)))continue;
   size_t start;if(code_range(data,size,eh,sym.st_value,sym.st_size,&start)&&pos>=start&&pos-start<=sym.st_size&&length<=sym.st_size-(pos-start)){
    if(result)*result=(FunctionSymbol){name,sym.st_value,sym.st_size,start};
    return 1;
   }
  }
 }return 0;
}
static int in_capture_function(const unsigned char *data,size_t size,const Elf64_Ehdr *eh,size_t pos,size_t length){
 return function_symbol(data,size,eh,pos,length,1,NULL);
}
