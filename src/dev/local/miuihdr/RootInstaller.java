package dev.local.miuihdr;

import java.io.IOException;
import java.util.Locale;

/** Select the installer belonging to the su provider, not a leftover binary. */
final class RootInstaller {
    enum Provider { MAGISK, KERNELSU, APATCH }
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
