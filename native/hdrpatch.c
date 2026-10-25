/* Resolve the function, then validate the HDR clamp's control/data flow.
 * No firmware addresses, fixed field offsets or fixed register allocation.
 * Unknown/ambiguous implementations never produce an output file. */
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct {
    const unsigned char *data;
    size_t size;
    Elf64_Ehdr eh;
} Elf;
typedef struct {
    size_t start, end;
    uint64_t va;
} Function;
typedef struct {
    size_t offset, target;
    uint64_t va;
    uint32_t original, patched;
} Match;
static int fail(const char *s) {
    fprintf(stderr, "PATCH_ERROR: %s; original retained\n", s);
    return 1;
}
static int range(const Elf *e, uint64_t off, uint64_t len) {
    return off <= e->size && len <= e->size - off;
}
static uint32_t word(const Elf *e, size_t off) {
    const unsigned char *p = e->data + off;
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static void putword(unsigned char *p, uint32_t w) {
    for (unsigned n = 0; n < 4; n++)
        p[n] = (unsigned char)(w >> (8 * n));
}
static Elf64_Phdr segment(const Elf *e, unsigned n) {
    Elf64_Phdr p;
    memcpy(&p, e->data + e->eh.e_phoff + n * sizeof(p), sizeof(p));
    return p;
}
static Elf64_Shdr section(const Elf *e, unsigned n) {
    Elf64_Shdr s;
    memcpy(&s, e->data + e->eh.e_shoff + n * sizeof(s), sizeof(s));
    return s;
}
/* Translate virtual addresses through PT_LOAD; never assume VA == file offset. */
static int executable_offset(const Elf *e, uint64_t va, uint64_t len, size_t *off) {
    unsigned count = 0;
    for (unsigned i = 0; i < e->eh.e_phnum; i++) {
        Elf64_Phdr p = segment(e, i);
        if (p.p_type != PT_LOAD || !(p.p_flags & PF_X) || va < p.p_vaddr)
            continue;
        uint64_t delta = va - p.p_vaddr;
        if (delta <= p.p_filesz && len <= p.p_filesz - delta) {
            *off = (size_t)(p.p_offset + delta);
            count++;
        }
    }
    return count == 1;
}
static const char *validate(Elf *e) {
    if (e->size < sizeof(e->eh))
        return "truncated ELF";
    memcpy(&e->eh, e->data, sizeof(e->eh));
    Elf64_Ehdr *h = &e->eh;
    if (memcmp(h->e_ident, ELFMAG, SELFMAG))
        return "malformed ELF header";
    if (h->e_ident[EI_CLASS] != ELFCLASS64 || h->e_ident[EI_DATA] != ELFDATA2LSB ||
        h->e_machine != EM_AARCH64)
        return "unsupported ELF architecture (requires ELF64 AArch64)";
    if (h->e_ident[EI_VERSION] != EV_CURRENT || h->e_version != EV_CURRENT || h->e_type != ET_DYN ||
        h->e_ehsize != sizeof(*h) || !h->e_phnum || h->e_phnum == PN_XNUM ||
        h->e_phentsize != sizeof(Elf64_Phdr) ||
        !range(e, h->e_phoff, (uint64_t)h->e_phnum * sizeof(Elf64_Phdr)))
        return "unsupported or malformed ELF";
    for (unsigned i = 0; i < h->e_phnum; i++) {
        Elf64_Phdr p = segment(e, i);
        if (p.p_type == PT_LOAD && (!range(e, p.p_offset, p.p_filesz) || p.p_filesz > p.p_memsz ||
                                    p.p_memsz > UINT64_MAX - p.p_vaddr))
            return "invalid load segment";
    }
    if (!h->e_shoff || !h->e_shnum)
        return "function symbols unavailable (section headers required)";
    if (h->e_shentsize != sizeof(Elf64_Shdr) ||
        !range(e, h->e_shoff, (uint64_t)h->e_shnum * sizeof(Elf64_Shdr)))
        return "malformed section table";
    for (unsigned i = 0; i < h->e_shnum; i++) {
        Elf64_Shdr s = section(e, i);
        if (s.sh_type != SHT_NOBITS && !range(e, s.sh_offset, s.sh_size))
            return "invalid section range";
    }
    return NULL;
}
static const char *functions(const Elf *e, Function *out, size_t *count) {
    const char prefix[] = "_ZN7android14SurfaceFlinger16renderScreenImplE";
    *count = 0;
    for (unsigned n = 0; n < e->eh.e_shnum; n++) {
        Elf64_Shdr s = section(e, n);
        if (s.sh_type != SHT_DYNSYM && s.sh_type != SHT_SYMTAB)
            continue;
        if (s.sh_entsize != sizeof(Elf64_Sym) || s.sh_size % sizeof(Elf64_Sym) ||
            s.sh_link >= e->eh.e_shnum)
            return "malformed function symbol table";
        Elf64_Shdr strings = section(e, s.sh_link);
        if (strings.sh_type != SHT_STRTAB)
            return "invalid symbol string table";
        for (uint64_t pos = 0; pos < s.sh_size; pos += sizeof(Elf64_Sym)) {
            Elf64_Sym sym;
            memcpy(&sym, e->data + s.sh_offset + pos, sizeof(sym));
            if (sym.st_name >= strings.sh_size)
                return "invalid symbol name";
            const char *name = (const char *)e->data + strings.sh_offset + sym.st_name;
            if (!memchr(name, 0, (size_t)(strings.sh_size - sym.st_name)))
                return "unterminated symbol name";
            if (strncmp(name, prefix, sizeof(prefix) - 1) || sym.st_shndx == SHN_UNDEF)
                continue;
            if (ELF64_ST_TYPE(sym.st_info) != STT_FUNC || !sym.st_size || (sym.st_value & 3) ||
                (sym.st_size & 3) || sym.st_shndx >= e->eh.e_shnum)
                return "invalid renderScreenImpl symbol";
            Elf64_Shdr code = section(e, sym.st_shndx);
            size_t off;
            if (!(code.sh_flags & SHF_EXECINSTR) || code.sh_type != SHT_PROGBITS ||
                sym.st_value < code.sh_addr || sym.st_value - code.sh_addr > code.sh_size ||
                sym.st_size > code.sh_size - (sym.st_value - code.sh_addr) ||
                !executable_offset(e, sym.st_value, sym.st_size, &off) ||
                off != code.sh_offset + (sym.st_value - code.sh_addr))
                return "function outside executable code";
            size_t i;
            for (i = 0; i < *count; i++)
                if (out[i].start == off && out[i].end == off + sym.st_size)
                    break;
            if (i < *count)
                continue; /* Deduplicate .symtab/.dynsym aliases. */
            if (*count == 32)
                return "too many renderScreenImpl implementations";
            out[(*count)++] = (Function){off, off + (size_t)sym.st_size, sym.st_value};
        }
    }
    return *count ? NULL : "renderScreenImpl function not found";
}
static int rd(uint32_t w) { return w & 31; }
static int rn(uint32_t w) { return (w >> 5) & 31; }
static int rm(uint32_t w) { return (w >> 16) & 31; }
static unsigned imm12(uint32_t w) { return (w >> 10) & 4095; }
static int opcode(uint32_t w, uint32_t mask, uint32_t op) { return (w & mask) == op; }
static int branch_target(size_t at, uint32_t ins, unsigned bits, unsigned shift, size_t *out) {
    int64_t imm = (ins >> shift) & ((1u << bits) - 1);
    if (imm & (1u << (bits - 1)))
        imm -= 1LL << bits;
    int64_t result = (int64_t)at + imm * 4;
    if (result < 0)
        return 0;
    *out = (size_t)result;
    return 1;
}
/* Only NOP padding is skipped. Every executable operation must be understood. */
static int next(const Elf *e, const Function *f, size_t *cursor, size_t *at, uint32_t *w) {
    while (*cursor < f->end) {
        *at = *cursor;
        *w = word(e, *cursor);
        *cursor += 4;
        if (*w != 0xd503201f)
            return 1;
    }
    return 0;
}
static size_t skip_nops(const Elf *e, const Function *f, size_t at) {
    while (at < f->end && word(e, at) == 0xd503201f)
        at += 4;
    return at;
}
/* if (!impl) goto done; predicate = impl->virtualPredicate();
 * if (!(predicate & 1)) goto done;                 <-- only patched instruction
 * if (limit <= 1 || args.option & 1) goto done;
 * args.brightness = min(args.brightness, args.limit);
 * All exit branches must join immediately after the brightness store. */
static int clamp(const Elf *e, const Function *f, size_t start, Match *m) {
    size_t cursor = start, at, done, branch, target, store_target, alt, store_at;
    uint32_t w, gate;
    int table, method, unit, limit, base, flag, brightness, result;
    unsigned limit_field, brightness_field;
    /* x0 must be a call result, not an unrelated pointer found in this function. */
    size_t producer = start;
    do {
        if (producer == f->start)
            return 0;
        producer -= 4;
    } while (word(e, producer) == 0xd503201f);
    if (!opcode(word(e, producer), 0xfc000000, 0x94000000))
        return 0;
#define NEXT()                                                                                     \
    do {                                                                                           \
        if (!next(e, f, &cursor, &at, &w))                                                         \
            return 0;                                                                              \
    } while (0)
    NEXT();
    if (!opcode(w, 0xff00001f, 0xb4000000) || !branch_target(at, w, 19, 5, &done) || done <= at ||
        done >= f->end)
        return 0;
    NEXT();
    if (!opcode(w, 0xffc003e0, 0xf9400000) || imm12(w) != 0 || rd(w) == 31)
        return 0;
    table = rd(w);
    NEXT();
    if (!opcode(w, 0xffc00000, 0xf9400000) || rn(w) != table || rd(w) == 31)
        return 0;
    method = rd(w);
    NEXT();
    if (!opcode(w, 0xfffffc1f, 0xd63f0000) || rn(w) != method)
        return 0;
    NEXT();
    branch = at;
    gate = w;
    if (!(opcode(w, 0xfff8001f, 0x36000000) && branch_target(at, w, 14, 5, &target)) &&
        !(opcode(w, 0xfc000000, 0x14000000) && branch_target(at, w, 26, 0, &target)))
        return 0;
    if (target != done)
        return 0;
    /* FMOV #1 and LDR are independent and may be scheduled in either order. */
    NEXT();
    uint32_t first = w;
    NEXT();
    uint32_t second = w;
    if (opcode(second, 0xffffffe0, 0x1e2e1000)) {
        w = first;
        first = second;
        second = w;
    }
    if (!opcode(first, 0xffffffe0, 0x1e2e1000) || !opcode(second, 0xffc00000, 0xbd400000))
        return 0;
    unit = rd(first);
    limit = rd(second);
    base = rn(second);
    limit_field = imm12(second);
    if (unit == limit || base == 31 || base == 0 || base == method || base == table)
        return 0;
    NEXT();
    if (!opcode(w, 0xffe0fc1f, 0x1e202000) || rn(w) != limit || rm(w) != unit)
        return 0;
    NEXT();
    if (!opcode(w, 0xff00001f, 0x5400000d) || !branch_target(at, w, 19, 5, &target) ||
        target != done)
        return 0;
    NEXT();
    if (!opcode(w, 0xffc00000, 0x39400000) || rn(w) != base || rd(w) == base || rd(w) == 31)
        return 0;
    flag = rd(w);
    NEXT();
    if (!opcode(w, 0xfff80000, 0x37000000) || rd(w) != flag ||
        !branch_target(at, w, 14, 5, &target) || target != done)
        return 0;
    NEXT();
    if (!opcode(w, 0xffc00000, 0xbd400000) || rn(w) != base)
        return 0;
    brightness = rd(w);
    brightness_field = imm12(w);
    if (brightness == limit || brightness_field == limit_field)
        return 0;
    NEXT();
    if (!opcode(w, 0xffe0fc1f, 0x1e202000) || rn(w) != brightness || rm(w) != limit)
        return 0;
    NEXT();
    if (!opcode(w, 0xffe0fc00, 0x1e204c00) || rn(w) != brightness || rm(w) != limit)
        return 0;
    result = rd(w);
    NEXT();
    if (!opcode(w, 0xfc000000, 0x14000000) || !branch_target(at, w, 26, 0, &store_target) ||
        store_target <= at || store_target >= done)
        return 0;
    NEXT();
    alt = at;
    if (!opcode(w, 0xffc00000, 0xbd400000) || rn(w) != base || imm12(w) != limit_field ||
        rd(w) != result)
        return 0;
    NEXT();
    store_at = at;
    if (!opcode(w, 0xffc00000, 0xbd000000) || rn(w) != base || imm12(w) != brightness_field ||
        rd(w) != result || skip_nops(e, f, store_target) != store_at ||
        skip_nops(e, f, done) != skip_nops(e, f, cursor) || alt >= store_at)
        return 0;
    /* Restoring our B must remain representable as the proven TBZ w0,#0 test. */
    size_t delta = done - branch;
    if (delta % 4 || delta / 4 >= 8192)
        return 0;
    m->offset = branch;
    m->target = done;
    m->va = f->va + (branch - f->start);
    m->original = 0x36000000 | ((uint32_t)(delta / 4) << 5);
    m->patched = 0x14000000 | (uint32_t)(delta / 4);
    if (gate != m->original && gate != m->patched)
        return 0;
    return 1;
#undef NEXT
}
int main(int argc, char **argv) {
    int probe = argc == 3 && !strcmp(argv[1], "probe");
    if (!probe && (argc != 4 || (strcmp(argv[1], "patch") && strcmp(argv[1], "restore"))))
        return fail("usage: hdrpatch probe input | patch|restore input output");
    FILE *in = fopen(argv[2], "rb");
    if (!in)
        return fail("cannot read input");
    if (fseek(in, 0, SEEK_END)) {
        fclose(in);
        return fail("seek failed");
    }
    long length = ftell(in);
    rewind(in);
    if (length < (long)sizeof(Elf64_Ehdr) || length > 128 * 1024 * 1024) {
        fclose(in);
        return fail("invalid ELF size");
    }
    size_t size = (size_t)length;
    unsigned char *data = malloc(size);
    if (!data) {
        fclose(in);
        return fail("out of memory");
    }
    if (fread(data, 1, size, in) != size) {
        fclose(in);
        free(data);
        return fail("short read");
    }
    fclose(in);
    Elf elf = {.data = data, .size = size};
    const char *error = validate(&elf);
    Function found[32];
    size_t nfunctions = 0;
    if (!error)
        error = functions(&elf, found, &nfunctions);
    if (error) {
        free(data);
        return fail(error);
    }
    Match match = {0};
    size_t count = 0;
    for (size_t n = 0; n < nfunctions; n++)
        for (size_t at = found[n].start; at < found[n].end; at += 4) {
            if (!opcode(word(&elf, at), 0xff00001f, 0xb4000000))
                continue;
            Match candidate;
            if (clamp(&elf, &found[n], at, &candidate)) {
                if (count && candidate.offset == match.offset)
                    continue;
                match = candidate;
                count++;
            }
        }
    if (count != 1) {
        free(data);
        return fail(count ? "ambiguous HDR clamp control flow"
                          : "HDR clamp implementation not recognized");
    }
    int wasPatched = word(&elf, match.offset) == match.patched;
    if (probe) {
        printf("PATCH_MATCH offset=0x%zx vaddr=0x%llx target_offset=0x%zx "
               "function=SurfaceFlinger::renderScreenImpl resolver=semantic "
               "architecture=ELF64_AARCH64 input=%s\n",
               match.offset, (unsigned long long)match.va, match.target,
               wasPatched ? "patched" : "original");
        free(data);
        return 0;
    }
    putword(data + match.offset, !strcmp(argv[1], "patch") ? match.patched : match.original);
    char temp[4096];
    if (snprintf(temp, sizeof(temp), "%s.tmp.%ld", argv[3], (long)getpid()) >= (int)sizeof(temp)) {
        free(data);
        return fail("output path too long");
    }
    int fd = open(temp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) {
        free(data);
        return fail("cannot create output");
    }
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, data + done, size - done);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            break;
        done += (size_t)n;
    }
    int ok = done == size && !fsync(fd);
    if (close(fd))
        ok = 0;
    free(data);
    if (!ok || rename(temp, argv[3])) {
        unlink(temp);
        return fail("output commit failed");
    }
    printf("PATCH_OK offset=0x%zx vaddr=0x%llx input=%s operation=%s resolver=semantic\n",
           match.offset, (unsigned long long)match.va, wasPatched ? "patched" : "original",
           argv[1]);
    return 0;
}
