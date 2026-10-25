package dev.local.miuihdr;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Select the installer belonging to the su provider, not a leftover binary. */
final class RootInstaller {
    static final String MODULE_ID="xiaomi17u_hdr_clamp_test";
    enum Provider { MAGISK, KERNELSU, APATCH }
    interface Runner { String run(String... args) throws Exception; }
    static Provider identify(String version) throws IOException {
        String value=version.toLowerCase(Locale.ROOT);
        if(value.contains("apatch")||value.contains("kernelpatch"))return Provider.APATCH;
        if(value.contains("kernelsu")||value.contains("sukisu")||value.contains("yukisu"))return Provider.KERNELSU;
        if(value.contains("magisk"))return Provider.MAGISK;
        throw new IOException("ROOT_PROVIDER_UNKNOWN: 无法确认当前 Root 管理器；ZIP 已保留，可在管理器中手动安装");
    }
    static String[] command(Provider provider,String binary,String zip) {
        return provider==Provider.MAGISK?new String[]{binary,"--install-module",zip}:new String[]{binary,"module","install",zip};
    }
    /** Enable only the newly installed generation; a stale module is not proof of success. */
    static void installAndEnable(Provider provider,String binary,Path zip,Path adbRoot,Runner runner,Consumer<String> report)throws IOException {
        String installId;
        try(ZipFile archive=new ZipFile(zip.toFile())) {
            ZipEntry entry=archive.getEntry("install.id");
            if(entry==null)throw new IOException("缺少本次生成标识，请重新生成模块");
            try(InputStream in=archive.getInputStream(entry)) {
                byte[] identity=in.readNBytes(129);
                if(identity.length>128)throw new IOException("生成标识过长");
                installId=new String(identity,StandardCharsets.UTF_8).trim();
            }
            if(!UUID.fromString(installId).toString().equals(installId))throw new IOException("无效生成标识");
        }catch(Exception e){throw new IOException("ROOT_INSTALL_INVALID: "+e.getMessage(),e);}
        try {
            String output=runner.run(command(provider,binary,zip.toString()));
            report.accept("INSTALL_RESULT=success\n"+output);
        }catch(Exception e) {
            report.accept("INSTALL_RESULT=failed\n"+e.getMessage());
            throw new IOException("ROOT_INSTALL_FAILED ("+provider+"): "+e.getMessage()+"\nZIP 已保留："+zip,e);
        }
        try {
            List<Path> installed=installedDirectories(adbRoot,installId);
            if(provider==Provider.MAGISK) {
                // Magisk exposes module state through marker files, with no enable CLI.
                for(Path module:installed)Files.deleteIfExists(module.resolve("disable"));
            }else {
                // The daemon also updates provider-owned state (notably APatch).
                report.accept(runner.run(binary,"module","enable",MODULE_ID));
                // Daemons enable modules/<id>; staged updates may also retain a disable marker.
                Path staged=adbRoot.resolve("modules_update").resolve(MODULE_ID);
                if(installed.contains(staged))Files.deleteIfExists(staged.resolve("disable"));
            }
            for(Path module:installedDirectories(adbRoot,installId)) {
                if(Files.exists(module.resolve("disable"),LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("模块仍处于禁用状态："+module);
            }
            report.accept("ENABLE_RESULT=success\nMODULE_ENABLED="+MODULE_ID+"\nREBOOT_REQUIRED=true");
        }catch(Exception e) {
            report.accept("ENABLE_RESULT=failed\n"+e.getMessage());
            throw new IOException("ROOT_ENABLE_FAILED ("+provider+"): 模块已安装，但未确认启用："+e.getMessage()+"\nZIP 已保留："+zip,e);
        }
    }
    private static List<Path> installedDirectories(Path adbRoot,String installId)throws IOException {
        List<Path> installed=new ArrayList<>();
        // A staged module replaces the current module at boot, so verify it last.
        for(String root:new String[]{"modules","modules_update"}) {
            Path module=adbRoot.resolve(root).resolve(MODULE_ID);
            if(!Files.exists(module,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isDirectory(module,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(module.resolve("module.prop"),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("模块安装目录无效："+module);
            Properties props=new Properties();
            try(Reader in=Files.newBufferedReader(module.resolve("module.prop"))){props.load(in);}
            if(!MODULE_ID.equals(props.getProperty("id")))throw new IOException("模块标识不匹配："+module);
            if(Files.exists(module.resolve("remove"),LinkOption.NOFOLLOW_LINKS))throw new IOException("模块正等待卸载："+module);
            installed.add(module);
        }
        if(installed.isEmpty())throw new IOException("安装器未写入模块目录");
        Path nextBoot=installed.get(installed.size()-1),identity=nextBoot.resolve("install.id");
        if(!Files.isRegularFile(identity,LinkOption.NOFOLLOW_LINKS)||Files.size(identity)>128||!installId.equals(Files.readString(identity).trim()))
            throw new IOException("安装目录不属于本次生成的模块："+nextBoot);
        // Never remove recovery or mount-provider markers to force a failed payload on.
        for(String marker:new String[]{"boot-pending","rollback.txt"})
            if(Files.exists(nextBoot.resolve(marker),LinkOption.NOFOLLOW_LINKS))throw new IOException("新模块存在未完成的启动或回退记录："+nextBoot.resolve(marker));
        return installed;
    }
    static String[] paths(Provider provider) {
        switch(provider) {
            case KERNELSU:return new String[]{"/data/adb/ksu/bin/ksud"};
            case APATCH:return new String[]{"/data/adb/apd","/data/adb/ap/bin/apd"};
            default:return new String[]{"/data/adb/magisk/magisk","/sbin/magisk","/debug_ramdisk/magisk"};
        }
    }
    static String name(Provider provider) {
        return provider==Provider.MAGISK?"magisk":provider==Provider.KERNELSU?"ksud":"apd";
    }
}
