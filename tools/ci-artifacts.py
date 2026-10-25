#!/usr/bin/env python3
"""Verify the unsigned cloud build and archive exact tracked source, never signing keys."""
import hashlib
import io
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def dex_classes(data):
    require(data[:4] == b"dex\n", "Unexpected DEX format")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings, types, classes = u32(0x3C), u32(0x44), u32(0x64)

    def descriptor(type_index):
        offset = u32(strings + 4 * u32(types + 4 * type_index))
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        return data[offset:data.index(b"\0", offset)].decode()

    return ({descriptor(u32(classes + 32 * n)) for n in range(u32(0x60))},
            {descriptor(n) for n in range(u32(0x40))})


def verify_aarch64(data, name):
    require(data[:6] == b"\x7fELF\x02\x01", name + " is not ELF64 little-endian")
    require(struct.unpack_from("<H", data, 18)[0] == 183, name + " is not AArch64")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<IIQQQQQQ", data, phoff + n * phsize) for n in range(phnum)]
    require(any(segment[0] == 1 for segment in loads), name + " has no PT_LOAD segments")
    for segment in loads:
        if segment[0] == 1:
            require(segment[7] >= 16384, name + " lacks 16 KB ELF page alignment")


def verify_apk(apk):
    raw = apk.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        expected = {
            "java_init.list": "dev.local.miuihdr.MiuiHook",
            "native_init.list": "libmiuihdr.so",
            "scope.list": "com.miui.screenshot",
        }
        for name, value in expected.items():
            require(archive.read("META-INF/xposed/" + name).decode().strip() == value, "Wrong modern entry: " + name)
        props = dict(line.split("=", 1) for line in archive.read("META-INF/xposed/module.prop").decode().splitlines())
        require(props.get("minApiVersion") == props.get("targetApiVersion") == "102", "Wrong API version")
        require(props.get("autoHotReload") == "false", "Native hooks cannot be hot reloaded")
        require("assets/xposed_init" not in archive.namelist() and "assets/native_init" not in archive.namelist(), "Legacy module entry remains")
        classes, references = set(), set()
        for name in archive.namelist():
            if name.endswith(".dex"):
                defined, used = dex_classes(archive.read(name))
                classes.update(defined)
                references.update(used)
        require("Ldev/local/miuihdr/MiuiHook;" in classes, "R8 removed module entry")
        require(not any(name.startswith("Lio/github/libxposed/") for name in classes), "Framework API was bundled")
        require(not any(name.startswith("Lde/robv/android/xposed/") or "AndroidAppHelper" in name for name in references), "Legacy framework reference remains")
        native = archive.getinfo("lib/arm64-v8a/libmiuihdr.so")
        require(native.compress_type == zipfile.ZIP_STORED, "Native module library must be uncompressed")
        name_length, extra_length = struct.unpack_from("<HH", raw, native.header_offset + 26)
        require((native.header_offset + 30 + name_length + extra_length) % 16384 == 0, "Native module library ZIP offset lacks 16 KB alignment")
        verify_aarch64(archive.read(native), "libmiuihdr.so")
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/mount-template.zip"))) as payload:
            verify_aarch64(payload.read("bin/hdrpatch"), "mount payload hdrpatch")
            require((payload.getinfo("bin/hdrpatch").external_attr >> 16) & 0o111, "Patch tool is not executable")
    print("PASS: API 102 metadata, R8 entry, compile-only API boundary, AArch64 native payload, 16 KB alignment")


def main():
    os.chdir(ROOT)
    apk = ROOT / "dist/unsigned.apk"
    require(apk.is_file(), "build.sh did not produce dist/unsigned.apk")
    verify_apk(apk)
    version = re.search(r'versionName\s*=\s*"([^"\n]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    require(re.fullmatch(r"[A-Za-z0-9.+-]+", version) is not None, "Unexpected version name")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    # The source ZIP must describe the exact tested commit, not a dirty working tree.
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--"], check=True, stdout=subprocess.DEVNULL)
    output = ROOT / "build/ci-artifacts"
    output.mkdir(parents=True, exist_ok=True)
    stem = "MI-Screen-HDR-Enhanced-" + version + "-api102-test"
    destination = output / (stem + "-unsigned.apk")
    shutil.copyfile(apk, destination)
    source = output / (stem + "-source.zip")
    subprocess.run(["git", "archive", "--format=zip", "--prefix=" + stem + "/", "--output=" + str(source), commit], check=True)
    info = output / "BUILD-INFO.txt"
    info.write_text("\n".join([
        "Test build only; no GitHub Release was created.",
        "APK: unsigned; sign locally before installing. No signing key is stored in these artifacts.",
        "Version: " + version,
        "Commit: " + commit,
        "Workflow run: " + os.environ.get("GITHUB_SERVER_URL", "https://github.com") + "/" + os.environ.get("GITHUB_REPOSITORY", "local") + "/actions/runs/" + os.environ.get("GITHUB_RUN_ID", "local"),
        "Toolchain: JDK 25 / Android SDK 37.0 / Build Tools 37.0.0 / NDK 28.2.13676358 / CMake 3.31.6",
        "Runtime: Android 14+ / arm64-v8a / libxposed API 102+",
        "Tests: synthetic ELF/control-flow fixtures, library discovery, installation recovery, API 102 hook chains, app runtime boundary.",
        "Phone testing has not been performed by CI; review screenshots/editing/mount behavior on the intended devices.",
        "",
    ]))
    checksums = output / "SHA256SUMS.txt"
    checksums.write_text("".join(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n" for path in (destination, source, info)))
    print("Collected unsigned APK, exact source archive, build provenance, and SHA-256 checksums:", output)


if __name__ == "__main__":
    main()
