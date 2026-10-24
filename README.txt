小米截屏HDR增强 / MI Screen HDR Enhanced 1.3.2

本机适配
小米 17 Ultra / nezha，HyperOS OS4.0.4.0.XPACNXM，Android 17（SDK 37）。
小米截屏：com.miui.screenshot，RELEASE-1.6.3.41-09181700 / 10603041。
安装 APK 后在 LSPosed 启用模块，仅勾选“小米截屏”。不替换系统 APK。
设置页“生成挂载模块”会读取本机库并生成对应 Magisk/KernelSU/APatch ZIP；“生成并安装挂载模块”会进一步安装。两者都需要本应用 Root 权限。
生成文件位于当前 Android 用户的 Download/MI-Screen-HDR-Enhanced/MI-Screen-HDR-Enhanced-mount-1.3.2.zip。

功能
- LSPosed native_init 注入 libmiuihdr.so，拦截截屏进程暂时关闭 HDR 的调用。
- 保留小米原生 JPG 保存及增益图。
- 修复编辑器重绘丢失增益图，以及带壳合成丢失增益图这两段流程。
- 带壳增益图按真实机身素材的缩放、边距、圆角同步处理；边框保持 SDR。
- 原生“带壳”按钮三态循环：带壳 → 带壳 · JPG → 带壳 · PNG HDR → 带壳（关闭）。不弹出菜单，状态只作用于当前编辑会话。SDR 图片对应显示 PNG。
- JPG 使用 Ultra HDR JPEG，透明外侧合成白底；PNG HDR 保留透明度和增益图。文件后缀及 MIME 随状态同步。PNG HDR 的显示依赖查看软件支持。
- 透明边框和标注按遮挡权重处理，不把屏幕高光直接套到机壳或标注上。
- 彩色轮廓泛光持续流动并跟随原生截图缩放、移动、退出动画。
- 辉光、辉光 HDR 亮度、HDR 标记可以分别切换。
- 捕获前移除上次辉光，同时排除预览图层。
- HDR 测试用真实 Ultra HDR 照片与 1/2/4 倍亮度阶梯。
- 单模式显示一个预览单元；对照显示两个相同太阳高光裁切，去除容器黑边。
- 可选取保存的图片检查增益图。有效增益不等于所有应用显示同样的亮度。

界面与素材来源
UI 框架：Google / AndroidX Jetpack Compose Material 3，Apache-2.0。
设计规范：Google Material 3 Expressive。
部分主题配置、浮动导航代码复用/改编自 SukiSU-Ultra；这是代码片段的出处，不是 UI 框架来源。对应代码遵循 GPL-3.0，完整模块源码随 APK 提供。
详细版本及文件路径见 assets/UI-SOURCE.txt。照片来源及 CC BY 4.0 许可见 assets/HDR-PHOTO-*。

重要适用范围
已移植用户提供的 xiaomi17u_hdr_clamp_test 底层修复工作方式，由 APK 对本机实际库生成对应挂载 ZIP，复用原模块 ID 升级。APK 不携带固定系统 SO。
底层生成不检查机型名称或预设固件哈希；按实际指令序列生成补丁，不适配/歧义/损坏作为错误退出。安装、启动时的校验来自本次生成的文件记录。
原库备份、未完成启动标记和启动监测提供错误回退；无法保证脚本运行前发生的故障即时恢复。详见 root-payload/README.txt。
模块针对这份固件和小米截屏版本。SO 的固定位置有系统库 SHA-256 和指令校验。
壳合成支持当前截屏的 SDR 基图 + 增益图，以及 sRGB / Display P3。不同增益计算原色或 HDR 基图方向暂不改写，并记录跳过原因。
无法从已丢失 HDR 数据的 SDR 截图恢复原始高光，也不保证每个平台或受保护画面都能捕获。

验证范围
截图、编辑、带壳和连续截图已在上述设备进行验证。设备若启用了其他截图替换模块，应先确认系统按键实际进入小米原生截图。
底层补丁生成与恢复逻辑通过静态验证和模拟测试；自动回退尚未做真实故障/冷启动验证。
本发布版不包含实验性的增益图重算算法。

回退
关闭 LSPosed 中本模块并结束、重启“小米截屏”进程即可停止 Hook。
底层挂载模块 ID 为 xiaomi17u_hdr_clamp_test，在 Magisk / KernelSU / APatch 中禁用并重启可停止覆盖。
卸载 APK 不会卸载既有底层修复模块。

构建
使用 Python 3、JDK 17、随附 Gradle wrapper（9.7.1）、Android SDK 37 / Build Tools 37.0.0、支持 API 34 ARM64 的 Android NDK。
设置 ANDROID_HOME 或在 local.properties 写 sdk.dir；设置 ANDROID_NDK_HOME。
运行 bash build.sh，会编译 native SO 与 Compose APK。普通电脑使用标准 SDK aapt2；本机另有 Termux ARM64 aapt2 适配。
使用自己的签名证书；源码包不包含本机私钥或密码。可用 Android apksigner 签名 dist/unsigned.apk。
本地维护签名可设置 HDR_KEYSTORE、HDR_PASSWORD_FILE；tools/SignApk.java 使用 PKCS12 中的 hdr-screenshot-local 别名。
SO 必须在 APK 内不压缩并按 16 KB 对齐，供 LSPosed 直接从 APK 加载。

测试入口
NativeProbeEntry 在原本非导出的 TakeScreenshotService 中处理 dev.local.miuihdr.ROOT_CAPTURE_TEST，仅 root/system 可进入；请求仍走小米原生 Messenger。
测试源码 ShellTest.java 需要在本机 app_process 中与自己的小米截屏 APK 一起运行，不随源码分发系统 APK 或机壳素材。

1.3.2 挂载兼容
根据当前 Root 提供者选择安装入口。沿用已有 Magic Mount、OverlayFS、Mountify 或兼容元模块，不再强制依赖 Mountify。
兼容安装器移动分区目录，保留挂载提供者的跳过标记。Mountify 手动选择未包含本模块时会报告原因，不修改全局配置。
安装后须经过下次启动验证才确认底层覆盖生效；本机仅验证 SukiSU + Mountify 的生成流程，其他组合属于模拟验证。
