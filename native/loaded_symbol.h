#ifndef MIUIHDR_LOADED_SYMBOL_H
#define MIUIHDR_LOADED_SYMBOL_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <string.h>

/* Resolve a named entry point in a library that is already loaded. The handle
 * pins the exact image for the lifetime of an installed hook. Never substitute
 * a library found on disk or an equally named symbol from a dependency. */
typedef struct {
    void *handle;
    void *address;
    uintptr_t offset;
    char path[4096];
} HdrLoadedFunction;

enum {
    HDR_SYMBOL_OK = 0,
    HDR_SYMBOL_NOT_LOADED = -10,
    HDR_SYMBOL_AMBIGUOUS = -11,
    HDR_SYMBOL_INVALID_IMAGE = -12,
    HDR_SYMBOL_UNAVAILABLE = -13,
    HDR_SYMBOL_MISSING = -14,
    HDR_SYMBOL_WRONG_OWNER = -15,
    HDR_SYMBOL_WRONG_ENTRY = -16
};

typedef struct {
    const char *library;
    uintptr_t address, bias;
    unsigned count;
    int invalid, executable;
    char path[4096];
} HdrLoadedSearch;

static int hdr_loaded_search(struct dl_phdr_info *info, size_t size, void *opaque) {
    (void)size;
    HdrLoadedSearch *search = opaque;
    const char *path = info->dlpi_name;
    if (!path || !*path) return 0;
    const char *base = strrchr(path, '/');
    if (strcmp(base ? base + 1 : path, search->library)) return 0;
    ++search->count;
    if (search->count != 1) return 0;
    size_t length = strlen(path);
    if (length >= sizeof(search->path) || !info->dlpi_phdr || !info->dlpi_phnum) {
        search->invalid = 1;
        return 0;
    }
    memcpy(search->path, path, length + 1);
    search->bias = (uintptr_t)info->dlpi_addr;
    for (unsigned i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || (ph->p_flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        if (ph->p_vaddr > UINTPTR_MAX - search->bias) {
            search->invalid = 1;
            continue;
        }
        uintptr_t start = search->bias + ph->p_vaddr;
        if (ph->p_memsz > UINTPTR_MAX - start) {
            search->invalid = 1;
            continue;
        }
        if (search->address >= start && search->address - start < ph->p_memsz)
            search->executable = 1;
    }
    return 0;
}

static int hdr_loaded_status(const HdrLoadedSearch *search) {
    if (!search->count) return HDR_SYMBOL_NOT_LOADED;
    if (search->count != 1) return HDR_SYMBOL_AMBIGUOUS;
    if (search->invalid) return HDR_SYMBOL_INVALID_IMAGE;
    return HDR_SYMBOL_OK;
}

static int hdr_resolve_loaded_function(const char *library, const char *symbol,
                                       HdrLoadedFunction *result) {
    memset(result, 0, sizeof(*result));
    HdrLoadedSearch before = {.library = library};
    dl_iterate_phdr(hdr_loaded_search, &before);
    int status = hdr_loaded_status(&before);
    if (status) return status;

    /* RTLD_NOLOAD must not load a second image into this linker namespace. */
    void *handle = dlopen(before.path, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    if (!handle) return HDR_SYMBOL_UNAVAILABLE;
    dlerror();
    void *address = dlsym(handle, symbol);
    if (dlerror() || !address) {
        dlclose(handle);
        return HDR_SYMBOL_MISSING;
    }

    /* Re-enumerate after pinning: reject unload/reload races, multiple loaded
     * images, data exports, and dependency-scope dlsym matches. */
    HdrLoadedSearch after = {.library = library, .address = (uintptr_t)address};
    dl_iterate_phdr(hdr_loaded_search, &after);
    status = hdr_loaded_status(&after);
    if (!status && (before.bias != after.bias || strcmp(before.path, after.path) || !after.executable))
        status = HDR_SYMBOL_WRONG_OWNER;
    Dl_info owner = {0};
    if (!status && (!dladdr(address, &owner) || owner.dli_saddr != address ||
                   !owner.dli_sname || strcmp(owner.dli_sname, symbol)))
        status = HDR_SYMBOL_WRONG_ENTRY;
    if (status) {
        dlclose(handle);
        return status;
    }
    result->handle = handle;
    result->address = address;
    result->offset = (uintptr_t)address - after.bias;
    memcpy(result->path, after.path, sizeof(result->path));
    return HDR_SYMBOL_OK;
}

static const char *hdr_symbol_error(int status) {
    switch (status) {
        case HDR_SYMBOL_NOT_LOADED: return "library not loaded";
        case HDR_SYMBOL_AMBIGUOUS: return "multiple loaded libraries have this name";
        case HDR_SYMBOL_INVALID_IMAGE: return "invalid loaded ELF mapping";
        case HDR_SYMBOL_UNAVAILABLE: return "loaded library is unavailable in this linker namespace";
        case HDR_SYMBOL_MISSING: return "required function symbol is missing";
        case HDR_SYMBOL_WRONG_OWNER: return "symbol is outside the selected library's executable segments";
        case HDR_SYMBOL_WRONG_ENTRY: return "resolved address is not the exact function entry";
        default: return "unknown resolver error";
    }
}
#endif
