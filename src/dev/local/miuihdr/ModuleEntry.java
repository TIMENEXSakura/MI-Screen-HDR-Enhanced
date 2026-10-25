package dev.local.miuihdr;
import io.github.libxposed.api.XposedModule;

/** Modern libxposed API 102 entry. This module never calls the legacy bridge. */
public final class ModuleEntry extends XposedModule {
 private String process;
 private boolean installed,eligible;
 @Override public void onModuleLoaded(ModuleLoadedParam param){
  process=param.getProcessName();
  eligible=!param.isSystemServer()&&(process.equals("com.miui.screenshot")||process.startsWith("com.miui.screenshot:"));
  if(!eligible){detach();return;}
  HookRuntime.initialize(this);
  HookRuntime.log("Module API="+getApiVersion()+" target=102 framework="+getFrameworkName()+" process="+process);
 }
 @Override public void onPackageReady(PackageReadyParam param){
  if(!eligible||installed||!param.getPackageName().equals("com.miui.screenshot"))return;
  installed=true;MiuiHook.install(param.getClassLoader(),process);
  HookRuntime.log("Modern interceptor hooks installed="+HookRuntime.count()+" nativeLoaded="+NativeBridge.loaded+" nativeGate="+NativeBridge.gate);
 }
 @Override public boolean onHotReloading(HotReloadingParam param){
  // Native callbacks and in-flight preview animations belong to this generation.
  // API 102 support does not imply it is safe to unload those while drawing.
  return false;
 }
}
