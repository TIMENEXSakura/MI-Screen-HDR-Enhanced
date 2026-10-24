小米截屏HDR增强 / MI Screen HDR Enhanced 1.4.5 — 动态生成的挂载模块

此 ZIP 由 Xposed APK 的“生成挂载模块”在本机生成。
APK 内只有生成器及脚本模板，不内置或分发某台设备的完整系统 SO。
按实际 ELF 中的指令序列定位原实验模块的亮度限制补丁；不检查机型名称或固定 ROM 白名单。
指令不存在、存在多处匹配、文件损坏或写入失败均作为生成错误退出，原库保持原样。
生成时同时保存原库和补丁库；安装时发现系统库已经变化，需要在 APK 中重新生成。

目标：按 SurfaceFlinger 实际加载映射和系统库目录定位 libsurfaceflinger.so。
搜索 system / system_ext / product / vendor / odm、system_root 别名及 APEX 下的 lib/lib64。
生成包用 target.path / payload.path 记录目标，安装、启动检查及回退复用同一位置。
当前可生成标准 system、system_ext、product、vendor 覆盖；APEX/ODM 等不支持的挂载位置明确报错。
指令补丁仍要求匹配的 ELF64 AArch64 实现；发现32位库不代表能套用64位补丁。
模块 ID 沿用 xiaomi17u_hdr_clamp_test，升级现有底层模块，避免两份库同时覆盖。
Magisk / KernelSU / APatch 使用各自已有的挂载提供者；不限定 Mountify，也不替换全局挂载配置。
原 hdr_screenshot_tonemap 属性模块在 APK 成功安装整合包后才被停用。

回退：损坏或上次启动未通过会在下次开机停止挂载。
启动监测发现挂载失败、SurfaceFlinger 多次重启、或 180 秒内未完成启动时标记禁用。
可验证的原库会尝试恢复为后续进程加载的文件；已运行的进程映射不会被强行替换或重启。
如果系统在监测脚本运行前就中断，只能在下一次启动阻止挂载；不承诺所有启动故障都可即时恢复。
正常禁用/卸载后重启，停止覆盖。Action 按钮显示生成和启动状态。

本次移植依据用户提供的 Xiaomi17U_OS4_HDR_SukiSU_Mountify_v0.1-experimen.zip：
SHA-256 b33cd087d7cf904533803d7a86145197c67bb351a7a73c68a4412497ab630aaf。
在提供的原库上生成的补丁与原 ZIP 中的 SO 完全一致，反向还原也完全一致。
设备实际冷启动及故障恢复尚未人为触发验证；未自动重启手机。

参考挂载规范：
https://kernelsu.org/guide/module.html
https://github.com/backslashxx/mountify
https://topjohnwu.github.io/Magisk/guides.html
https://apatch.dev/apm-guide.html

1.3.2 挂载兼容
根据当前 Root 提供者选择安装入口。沿用已有 Magic Mount、OverlayFS、Mountify 或兼容元模块，不再强制依赖 Mountify。
兼容安装器移动分区目录，保留挂载提供者的跳过标记。Mountify 手动选择未包含本模块时会报告原因，不修改全局配置。
安装后须经过下次启动验证才确认底层覆盖生效；本机仅验证 SukiSU + Mountify 的生成流程，其他组合属于模拟验证。
