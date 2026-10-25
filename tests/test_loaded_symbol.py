"""Test loaded-library function resolution without loading or hooking system SOs."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CC = shlex.split(os.environ.get("CC", "cc"))
SYMBOL = "_ZN7android21SurfaceComposerClient17setMiHdrInfoFlagsEii"

RUNNER = r'''
#include "loaded_symbol.h"
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char **argv) {
    void *handles[2] = {0};
    for (int i = 3; i < argc; ++i) {
        handles[i - 3] = dlopen(argv[i], RTLD_NOW | RTLD_LOCAL);
        if (!handles[i - 3]) { fprintf(stderr, "fixture dlopen: %s\n", dlerror()); return 2; }
    }
    HdrLoadedFunction result;
    int status = hdr_resolve_loaded_function("libgui.so", argv[1], &result);
    int expected = atoi(argv[2]);
    if (status != expected) {
        fprintf(stderr, "expected %d, got %d (%s)\n", expected, status, hdr_symbol_error(status));
        return 3;
    }
    if (!status) {
        if (!result.handle || !result.address || !result.path[0]) return 4;
        /* The resolver's handle must keep this exact image alive. */
        for (int i = 3; i < argc; ++i) { dlclose(handles[i - 3]); handles[i - 3] = NULL; }
        int (*function)(int, int) = (int (*)(int, int))result.address;
        if (function(20, 3) != 43) return 5;
        printf("offset=0x%lx\n", (unsigned long)result.offset);
        dlclose(result.handle);
    } else if (result.handle || result.address || result.offset || result.path[0]) return 6;
    for (int i = 3; i < argc; ++i) if (handles[i - 3]) dlclose(handles[i - 3]);
    return 0;
}
'''

# Android's linker namespace permits /data, but may reject a /tmp alias.
build = ROOT / "build"
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="hdr-loaded-symbol-", dir=build) as directory:
    temp = Path(directory)
    runner_source = temp / "runner.c"
    runner_source.write_text(RUNNER)
    runner = temp / "runner"
    subprocess.run(CC + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-I", str(ROOT / "native"),
                         str(runner_source), "-ldl", "-o", str(runner)], check=True)

    def library(name, contents, extra=()):
        path = temp / name / "libgui.so"
        path.parent.mkdir()
        source = path.with_suffix(".c")
        source.write_text(contents)
        subprocess.run(CC + ["-shared", "-fPIC", "-O0", str(source), "-o", str(path)] + list(extra), check=True)
        return path

    function = f'int gate(int flags, int mask) __asm__("{SYMBOL}");\nint gate(int flags, int mask) {{ return (flags << 1) | mask; }}\n'
    first = library("original", function)
    # Change code layout before the export; symbol lookup must derive its new RVA.
    shifted = library("shifted", '__asm__(".text\\n.balign 4096\\n.space 4096\\n");\n' + function)
    missing = library("missing", "int unrelated(int value) { return value; }\n")
    overload = library("overload", function.replace("Eii", "Eij"))
    data = library("data", f'int gate __asm__("{SYMBOL}") = 7;\n')

    dependency = temp / "libdependency.so"
    dependency_source = dependency.with_suffix(".c")
    dependency_source.write_text(function + "int fixture_dependency(void) { return 0; }\n")
    subprocess.run(CC + ["-shared", "-fPIC", str(dependency_source), "-o", str(dependency)], check=True)
    borrowed = library("borrowed", "extern int fixture_dependency(void); int anchor(void) { return fixture_dependency(); }\n",
                       [str(dependency), "-Wl,-rpath," + str(temp)])

    checks = 0

    def check(name, expected, *libraries, symbol=SYMBOL):
        global checks
        result = subprocess.run([str(runner), symbol, str(expected)] + [str(p) for p in libraries],
                                text=True, capture_output=True)
        assert result.returncode == 0, (name, result.returncode, result.stdout, result.stderr)
        checks += 1
        print("PASS", name, result.stdout.strip())
        return result.stdout.strip()

    check("do not load a library that is only present on disk", -10)
    initial_offset = check("resolve exact API and retain loaded image", 0, first)
    changed_offset = check("resolve API after code relocation", 0, shifted)
    assert initial_offset != changed_offset, (initial_offset, changed_offset)
    check("reject absent API", -14, missing)
    check("reject another overload", -14, overload)
    check("reject data export with matching name", -15, data)
    check("reject dependency export with matching name", -15, borrowed)
    check("reject multiple loaded libgui images", -11, first, shifted)
    print("ALL PASS", checks)
