#!/usr/bin/env python3
"""Host regression test for API 102 hooks; requires Java 17+, Android SDK and resolved libxposed AAR.

Run Gradle compileReleaseJavaWithJavac first, then:
  ANDROID_HOME=/path/to/sdk python3 tests/test_libxposed_hooks.py
HDR_XPOSED_API_JAR and HDR_PLATFORM_JAR can override dependency locations.
Only collaborators are stubbed; PrecisionHooks and the official API are compiled unmodified.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/app/Application.java": "package android.app; public class Application extends android.content.Context { void attach(android.content.Context context) {} }",
    "android/graphics/Bitmap.java": """package android.graphics; public final class Bitmap {
        public enum CompressFormat {JPEG, PNG}
        final boolean hdr; public Bitmap(boolean hdr){this.hdr=hdr;}
        public boolean isRecycled(){return false;} public boolean hasGainmap(){return hdr;}
        public boolean compress(CompressFormat format,int quality,java.io.OutputStream stream){return true;}
    }""",
    "dev/local/miuihdr/HookConfig.java": """package dev.local.miuihdr; final class HookConfig {
        static boolean active=true; boolean precision;
        static HookConfig read(android.content.Context context){HookConfig config=new HookConfig();config.precision=active;return config;}
    }""",
    "dev/local/miuihdr/HdrLog.java": "package dev.local.miuihdr; final class HdrLog {static void log(String text){}}",
    "dev/local/miuihdr/GainmapPrecision.java": "package dev.local.miuihdr; final class GainmapPrecision {static android.graphics.Bitmap tryApply(android.graphics.Bitmap bitmap){return bitmap;}}",
    "dev/local/miuihdr/HdrJpeg444.java": """package dev.local.miuihdr; final class HdrJpeg444 {
        static final byte[] DATA={1,2,3};static boolean fail;static Runnable duringEncode;
        static byte[] encode(android.graphics.Bitmap bitmap){if(fail)throw new IllegalStateException("encoder failure");if(duringEncode!=null)duringEncode.run();return DATA;}
    }""",
}


def main():
    sdk = Path(os.environ.get("ANDROID_HOME", "/nonexistent"))
    platform = Path(os.environ["HDR_PLATFORM_JAR"]) if "HDR_PLATFORM_JAR" in os.environ else next(iter(sorted(sdk.glob("platforms/android-*/android.jar"), reverse=True)), None)
    if platform is None or not platform.is_file():
        raise SystemExit("Set ANDROID_HOME or HDR_PLATFORM_JAR to an Android platform JAR")
    java_home = Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else None
    tool = lambda name: str(java_home / name) if java_home else name
    with tempfile.TemporaryDirectory(prefix="miuihdr-api102-") as temp:
        temp = Path(temp)
        if "HDR_XPOSED_API_JAR" in os.environ:
            api = Path(os.environ["HDR_XPOSED_API_JAR"])
        else:
            gradle = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
            aars = list(gradle.glob("caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0/*/*.aar"))
            if not aars:
                raise SystemExit("Run Gradle compileReleaseJavaWithJavac to resolve API 102.0.0, or set HDR_XPOSED_API_JAR")
            api = temp / "api-102.0.0.jar"
            api.write_bytes(zipfile.ZipFile(aars[0]).read("classes.jar"))
        sources = []
        for relative, source in STUBS.items():
            path = temp / "src" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
        sources += [str(ROOT / "src/dev/local/miuihdr" / name) for name in ("PrecisionHooks.java", "WeakIdentityMap.java")]
        sources.append(str(ROOT / "tests/libxposed/PrecisionHooksTest.java"))
        classes = temp / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(map(str, (api, platform)))
        subprocess.run([tool("javac"), "-source", "17", "-target", "17", "-cp", classpath, "-d", str(classes), *sources], check=True)
        subprocess.run([tool("java"), "-cp", str(classes) + os.pathsep + classpath, "dev.local.miuihdr.PrecisionHooksTest"], check=True)
        # Compile and run the actual shared logger/root helper in a separate JVM
        # with no libxposed or MiuiHook classes available at all.
        plain = temp / "plain-app"
        plain.mkdir()
        log_stub = temp / "Log.java"
        log_stub.write_text("package android.util; public final class Log {public static int calls; public static int i(String tag,String text){calls++;return 0;}}")
        subprocess.run([tool("javac"), "-source", "17", "-target", "17", "-cp", str(platform), "-d", str(plain), str(log_stub),
                        str(ROOT / "src/dev/local/miuihdr/HdrLog.java"), str(ROOT / "src/dev/local/miuihdr/NativeBridge.java"),
                        str(ROOT / "tests/libxposed/PlainAppLogTest.java")], check=True)
        subprocess.run([tool("java"), "-cp", str(plain) + os.pathsep + str(platform), "dev.local.miuihdr.PlainAppLogTest"], check=True)
    metadata = ROOT / "resources/META-INF/xposed"
    assert (metadata / "java_init.list").read_text().strip() == "dev.local.miuihdr.MiuiHook"
    assert (metadata / "native_init.list").read_text().strip() == "libmiuihdr.so"
    assert (metadata / "scope.list").read_text().strip() == "com.miui.screenshot"
    props = dict(line.split("=", 1) for line in (metadata / "module.prop").read_text().splitlines())
    assert props["minApiVersion"] == props["targetApiVersion"] == "102"
    assert props["autoHotReload"] == "false"
    assert not (ROOT / "assets/xposed_init").exists()
    assert not (ROOT / "assets/native_init").exists()
    assert not any("de.robv.android.xposed" in source.read_text() for source in (ROOT / "src").rglob("*.java"))
    assert not any("MiuiHook." in source.read_text() for source in (ROOT / "src").rglob("*.java"))
    print("PASS: modern module metadata, native entry, scope, no legacy imports or entry-class logging dependencies")


if __name__ == "__main__":
    main()
