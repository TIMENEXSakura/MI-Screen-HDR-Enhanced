# MI Screen HDR Enhanced

**小米截屏HDR增强**：通过 LSPosed Hook 和原生 SO 注入，为小米原生截屏保留 HDR 增益图，并修复编辑、带壳导出中的 HDR 信息丢失。不替换系统截图 APK。

## 下载与安装

从 [Releases](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/releases) 下载 APK，在 LSPosed 中启用模块，作用域选择 **小米截屏（`com.miui.screenshot`）**。

需要底层修复时，在应用设置中授予 Root 权限，然后选择：

- **生成挂载模块**：读取当前设备的库并生成 KSU / Magisk ZIP，保存到当前用户的 `Download/MI-Screen-HDR-Enhanced`。
- **生成并安装挂载模块**：生成后调用 Root 管理器安装。

公开发布包不携带完整系统 SO；挂载模块由 APK 在设备上生成。不要给其他设备安装别人生成的挂载 ZIP。

## 功能

- 保留小米原生截屏、编辑和保存流程中的 HDR 增益图。
- 修复编辑器重绘、带壳合成和透明边缘处理中的增益图丢失。
- 原生带壳按钮按 **JPG → 透明 PNG HDR → 关闭** 循环切换。
- 彩色流动泛光沿缩略图轮廓跟随缩放、移动和退出动画；辉光及其 HDR 亮度可分别关闭。
- 连续截图前清理上一次预览效果，并排除预览图层。
- 内置 Ultra HDR 照片、1× / 2× / 4× 亮度阶梯及截图增益检查。
- APK 按实际库指令生成底层补丁，不按机型名称限制；匹配失败、指令歧义和文件损坏会报错退出。
- 生成的挂载模块包含原库备份、启动监测和错误回退。

## 兼容性与验证范围

已验证环境：小米 17 Ultra（nezha），HyperOS OS4.0.4.0.XPACNXM，Android 17 / SDK 37；小米截屏 RELEASE-1.6.3.41-09181700（10603041）。

**底层补丁生成不检查机型，并不代表全部 Hook 已兼容所有固件。** 当前截图应用类名和原生 HDR 开关仍有版本依赖，原生固定位置使用库哈希和指令校验。未知版本会跳过对应 Hook。

截图、编辑、带壳和连续截图经过上述设备验证。启动恢复逻辑通过模拟测试，但尚未做真实故障和冷启动恢复验证。此发布版不包含实验性的增益图重算算法。

PNG HDR 是否显示正确取决于查看软件。增益图非零区域包含色调映射补偿，不能单独作为发光像素位置的判据。

如果启用了其他截图替换模块，请确认系统按键实际进入小米原生截图。受保护或已经变成 SDR 的内容不保证能取得原始 HDR 信息。

## 从源码构建

需要 Python 3、JDK 17、Android SDK 37、Build Tools 37.0.0，以及支持 API 34 / ARM64 的 Android NDK。Gradle 9.7.1 wrapper 已包含。

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
bash build.sh
```

也可通过 `local.properties` 设置 `sdk.dir`。构建脚本先编译原生 Hook 和补丁生成器，再打包挂载模板与 APK。普通电脑使用 NDK；Termux 构建路径保留在脚本中。

无私有签名配置时，输出为 `dist/unsigned.apk`，使用自己的 Android 签名证书签名。私钥与密码不在仓库中。详情见 [README.txt](README.txt)。

SO 在 APK 中需保持不压缩并按 16 KB 对齐，供 LSPosed 直接加载。

## 测试与回退

运行不接触真实系统的恢复逻辑模拟测试：

```sh
python3 tests/test_mount_recovery.py
```

`tests/ShellTest.java` 和 `tests/HdrInspect.java` 用于设备侧验证。带壳测试需自行提供对应的小米截图 APK 和机壳素材，仓库不分发这些文件。

停用应用 Hook：在 LSPosed 中关闭模块并重启小米截屏进程。停用底层覆盖：在 Root 管理器中禁用 `xiaomi17u_hdr_clamp_test` 并重启设备。卸载 APK 不会卸载已安装的挂载模块。

## 许可证与来源

项目源码按 [GNU GPL v3](LICENSE) 发布。第三方代码与素材保留各自许可证：

- UI 框架为 **Google / AndroidX Jetpack Compose Material 3**，设计规范为 **Material 3 Expressive**。
- 部分主题及浮动导航代码改编自 **SukiSU-Ultra**（GPL-3.0）。SukiSU 是这些代码片段的来源，并非 UI 框架的名称；具体版本与路径见 [UI-SOURCE.txt](assets/UI-SOURCE.txt)。
- MaterialKolor：MIT。
- HDR 照片：Mishaal Rahman，CC BY 4.0，见 [素材说明](assets/HDR-PHOTO-CREDIT.txt)。
- Xposed API：Apache-2.0，见 [第三方说明](NOTICE.txt)。

本项目为独立项目，与小米、Google、SukiSU 或 LSPosed 无官方隶属关系。
