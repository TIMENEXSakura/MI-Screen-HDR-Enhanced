# MI Screen HDR Enhanced

**小米截屏HDR增强**：通过 LSPosed Hook 和原生 SO 注入，为小米原生截屏保留 HDR 增益图，并修复编辑、带壳导出中的 HDR 信息丢失。不替换系统截图 APK。

## 下载与安装

从 [Releases](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/releases) 下载 APK，在 LSPosed 中启用模块，作用域选择 **小米截屏（`com.miui.screenshot`）**。

需要底层修复时，在**首页状态卡 → 安装**选择：

- **自动识别并修补**：检查 SurfaceFlinger 实际加载的 SO，再按 ELF 函数及指令关系确认实现位置。
- **选择原始 SO 文件**：选择本机固件的原始库，校验与当前设备一致后修补。

生成完成后自动调用 Magisk / KernelSU / APatch 安装器，ZIP 和定位报告保存在当前用户的 `Download/MI-Screen-HDR-Enhanced`。安装器失败时保留 ZIP。安装完成需要下次启动加载；应用不会自动重启设备或显示服务。

公开发布包不携带完整系统 SO；挂载模块由 APK 在设备上生成。不要给其他设备安装别人生成的挂载 ZIP。

## 功能

- 保留小米原生截屏、编辑和保存流程中的 HDR 增益图。
- 截图出现、缩入角落与退出时，预览图独立启用 HDR 显示；关闭 HDR 辉光不关闭图片 HDR。
- 修复编辑器重绘、带壳合成和透明边缘处理中的增益图丢失。
- 原生带壳按钮按 **JPG → 透明 PNG HDR → 关闭** 循环切换。
- 彩色流动泛光沿缩略图轮廓跟随缩放、移动和退出动画；辉光及其 HDR 亮度可分别关闭。
- 连续截图前清理上一次预览效果，并排除预览图层。
- 内置 Ultra HDR 照片、1× / 2× / 4× 亮度阶梯及截图增益检查。
- APK 按实际库指令生成底层补丁，不按机型名称限制；匹配失败、指令歧义和文件损坏会报错退出。
- 生成的挂载模块包含原库备份、启动监测和错误回退。

## 1.5.1 测试版：实现定位与编辑页 HDR

本版从正式标签 **v1.4.5** 独立开发，未合入 1.4.6 CI 修改。

- 按 SukiSU-Ultra 的 Material 界面实现改编主页状态卡、独立安装选项页、实时日志页和关于页。点击主页状态卡，选择自动识别或原始 SO，再点“下一步”执行修补并自动安装。
- 主页右上角新增“停止系统截屏”，用于下一次截图重新加载 Hook；强制停止会丢弃未保存的编辑。
- 升级为 **LSPosed · API 102**：使用现代生命周期和 interceptor，移除 legacy API 82 桥接、旧入口及旧版元数据。需要支持 API 102 的 LSPosed 实现；不启用模块热卸载。接口依赖为官方 `libxposed` API。

- 原生 HDR gate 使用 `SurfaceComposerClient::setMiHdrInfoFlags(int,int)` 的 ABI 符号动态解析，检查目标属于已加载 ELF 的可执行段；移除固定地址、SDK 和固件哈希白名单。
- 补丁生成器增加 ELF 函数范围及 AArch64 数据流校验，确认捕获中的亮度比较、选择、写回和分支目标。允许函数搬移、不同寄存器、结构体/vtable 偏移、比较次序以及插入 NOP；旧版完整字节特征保留为严格回退。匹配失败或多个实现匹配仍不写入。
- 枚举显示服务已加载的其他 SO，不再只接受 `libsurfaceflinger.so` 文件名。导入库必须与本机可见库或其经校验还原的原版一致。
- 删除按固定地址修改显示服务缓存的代码。HDR 属性在下次启动时供显示服务读取。
- 安装入口移到首页；自动识别和导入 SO 均在修补后调用现有 Root 安装器。
- 捕获时读取对应屏幕的**当前** HDR/SDR 亮度比例。自动模式保留有效原始比例，仅在原始比例缺失/无效时使用屏幕值；设置支持手动覆盖。只调整已有增益图的亮度元数据，不生成高光像素，也不绕过受保护内容限制。
- HDR 标记等缩入角落的原生动画结束后再渐显；旧截图的回调不能触发新截图的标记。
- 同时处理旧版 `ScreenEditorView` 的合成位图与新版 `ImageEditorView` 的原图直绘，编辑页和带壳预览保持 HDR；拍下一张时只清理临时缩略图，不关闭正在显示的编辑页 HDR。
- 从缩略图进入编辑页时，按该次截图对应的过渡 Binder 传递 HDR 范围，在内容首次附着前设置窗口，减少中途由 SDR 切回 HDR 的闪烁；有效性以真实增益图为依据。

验证范围见 [1.5.1 验证记录](tests/VALIDATION-v1.5.1.txt)。这不是任意固件的通用反编译器：完全不同的实现、缺少可验证特征或多个候选仍会停止并输出定位报告。目前只完成本机和合成 ELF 变体测试，其他手机的真实 SO 尚待验证。杜比内容若返回空白增益图，倍率调整不能恢复已缺失的数据。动画、编辑页和带壳预览的实际屏幕观感留给用户测试。

当前亮度比例依据 Android [Display.getHdrSdrRatio](https://developer.android.com/reference/android/view/Display#getHdrSdrRatio())；未用最大理论亮度代替当前屏幕值。

## 1.4.5：HDR 预览与处理提速

预览沿用小米的原图、增益图和圆角动画，从第一帧起独立申请 HDR 窗口及图片所需亮度范围；移除预览 View 的中间图层缓存，保持直接硬件绘制。小米更新窗口触摸区域时继续保留 HDR 参数。预览退出、下一次捕获及切换 SDR 时恢复原窗口参数，原有预览图层排除机制继续生效。HDR 辉光开关只控制外圈光效，不再决定预览图是否使用 HDR。

这修正了此前预览 HDR 依赖辉光、启动时机偏晚的问题。使用 Android 的 [Ultra HDR 窗口显示方式](https://developer.android.com/media/grow/ultra-hdr/display)，不对预览重复叠加增益，也不改变文件曝光值。窗口生命周期已通过设备侧测试；真实屏幕观感、缩放与连续截图交由用户在发布后测试，尚未宣称各机型均无过曝。

像素优化使用 8 位输入组合的精确查表，并跳过普通像素的重复重算；JPEG 元数据改由微型占位图生成，避免把大图完整编码两次。底图和增益图依旧保持原尺寸、质量 100 和 4:4:4，未降低采样或质量。

本机同一张 1200×2608 已知 HDR 图，2 次预热、7 次测量的中位数：增益重算 **265.0 → 34.4 ms**，JPEG 编码 **47.6 → 29.7 ms**。10 组样本共 209 个像素、元数据及图像文件与优化前逐字节一致。这是离线处理环节的测量，不是按键到保存完成的总延迟，也不代表所有图片都能获得相同提速。详情见 [验证记录](tests/VALIDATION-v1.4.5.txt)。

## 像素增益优化开关（1.4 开发版起，随 1.4.5 发布）

设置中新增 **像素增益优化**，默认关闭，下次截图生效。开启后在原生捕获完成时逐像素重建 HDR，将普通区域的补偿移回 SDR 底图，并在保存 JPG 时对底图及增益图使用质量 100、4:4:4 编码，保留原尺寸、色域和 HDR 元数据。无需先把 PNG 写入磁盘；直接使用同一份未压缩位图完成等价处理。

普通 SDR 截图保持原流程。开启此项会改变 SDR 底图及中间显示亮度下的观感，并增加保存耗时、内存和 JPG 体积。PNG 像素无损不代表元数据无限精度；JPG 仍有量化误差，4:4:4 不等于无损。已模糊、低分辨率或接近 SDR 白色的输入，不能恢复原始场景的绝对像素真值。

全分辨率 sRGB / Display P3 不透明截图经过验证；不支持的输入、元数据或编码错误会在写文件前沿用原流程。带壳透明 PNG 使用已有透明合成路径。写文件本身失败时报告保存失败，不在半成品后拼接另一个 JPG。

新增的 libjpeg-turbo 3.1.2 静态编码器不修改系统库；来源及许可见 [third_party/libjpeg-turbo](third_party/libjpeg-turbo/SOURCE.txt)。另修正了替代色域 API 的 Android 版本判断。实验推导和复现工具见 [像素增益实验](experiments/pixel-gainmap/README.md)。

## 1.3.2：Root 管理器和挂载方式兼容

根据当前 `su` 提供者选择 Magisk、KernelSU（含 SukiSU / YukiSU 标识）或 APatch 安装入口，不因残留的其他管理器二进制而切换安装器。无法识别或自动安装失败时保留 ZIP，供用户在自己的管理器中手动安装。

模块使用标准目录结构，由用户现有的 Magic Mount、OverlayFS、Mountify 或兼容元模块负责挂载，不再强制要求 KernelSU 使用 Mountify。校验同时处理 `system/<partition>` 和安装器移到模块根目录的 `<partition>` 布局；保留提供者已有的 `skip_mount` 标记。没有可用挂载提供者的环境仍需先在 Root 管理器中配置，安装成功不等于补丁已经生效。

Mountify 手动模式未选中本模块或挂载已关闭时会给出具体原因，不修改用户的全局选择。启动后确认目标库哈希和 SurfaceFlinger 稳定性；回退只尝试覆盖本模块的确切输出，不卸载共享挂载点。实时恢复失败时保留下一次启动的停用标记。

安装输出会追加到 `mount-diagnostics-1.3.2.txt`；模块的 `mount-backend.txt` 记录 Root 提供者、元模块和可见挂载信息。属性工具支持框架各自的路径及命令入口。APK 使用文件接收安装输出，避免日志填满管道后等待超时。

实现依据：[Magisk 模块指南](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU 元模块指南](https://kernelsu.org/guide/metamodule.html)、[APatch 模块指南](https://apatch.dev/apm-guide.html)、[Mountify 的跳过与选择规则](https://github.com/backslashxx/mountify#usage)。

## 1.3.1：库路径自动定位

优先检查 SurfaceFlinger 实际加载的库，再搜索 `system`、`system_ext`、`product`、`vendor`、`odm`、`system_root` 别名和 APEX 的 `lib` / `lib64` 目录。别名去重后，按实际位置生成挂载目录，安装、启动检查及回退均使用该路径。

未找到库、架构不支持、指令不匹配、多个目标匹配和挂载位置不支持会分别报告。定位详情保存为 `Download/MI-Screen-HDR-Enhanced/mount-diagnostics-<版本>.txt`。当前标准覆盖支持 system / system_ext / product / vendor；发现 APEX / ODM 库不等于已有对应挂载支持。

路径兼容不改变指令补丁的架构要求：仍需 ELF64 AArch64 及匹配的指令特征。应用最低 Android 版本仍为 Android 14 / API 34。

## 兼容性与验证范围

已验证环境：小米 17 Ultra（nezha），HyperOS OS4.0.4.0.XPACNXM，Android 17 / SDK 37；小米截屏 RELEASE-1.6.3.41-09181700（10603041）。

**底层补丁生成不检查机型，并不代表全部 Hook 已兼容所有固件。** 截图应用中的部分类名仍有版本依赖；原生 gate 按 ABI 符号解析，底层补丁需通过实现特征校验。未知版本会跳过对应 Hook 或报告无法识别。

此前版本的截图、编辑、带壳和连续截图经过上述设备验证。1.4.5 完成图像算法、编码与窗口生命周期测试，新增预览的屏幕观感和完整连续截屏测试待用户验证。启动恢复逻辑通过模拟测试，但尚未做真实故障和冷启动恢复验证。像素增益优化属于可选实验功能，默认关闭。

多框架安装入口、目录迁移和失败处理经过模拟测试；本机只具备 SukiSU + Mountify，其他 Root / 挂载组合没有逐一做真机冷启动验证。

PNG HDR 是否显示正确取决于查看软件。增益图非零区域包含色调映射补偿，不能单独作为发光像素位置的判据。

如果启用了其他截图替换模块，请确认系统按键实际进入小米原生截图。受保护或已经变成 SDR 的内容不保证能取得原始 HDR 信息。

## 从源码构建

1.5.1 使用 [GitHub Actions](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/actions) 云构建，工作流见 [.github/workflows/android-v151.yml](.github/workflows/android-v151.yml)。每次构建记录源代码提交、完整编译与测试日志，并提供未签名 APK、源码 ZIP、校验值及 `build-info.json`。云端不持有发布私钥；发布时下载对应 CI 产物并沿用原证书签名，安装请使用 Release 中的已签名 APK。云端检查不代替真机 HDR 显示与挂载验证。

需要 Python 3、JDK 17、Android SDK 37、Build Tools 37.0.0，以及支持 API 34 / ARM64 的 Android NDK、CMake 和构建工具。Gradle 9.7.1 wrapper 已包含。

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
bash build.sh
```

也可通过 `local.properties` 设置 `sdk.dir`。构建脚本按 SHA-256 校验下载固定版本 libjpeg-turbo 源码，先编译静态 JPEG 编码器、原生 Hook 和补丁生成器，再打包挂载模板与 APK。普通电脑使用 NDK；Termux 构建路径保留在脚本中。

无私有签名配置时，输出为 `dist/unsigned.apk`，使用自己的 Android 签名证书签名。私钥与密码不在仓库中。详情见 [README.txt](README.txt)。

SO 在 APK 中需保持不压缩并按 16 KB 对齐，供 LSPosed 直接加载。

## 测试与回退

运行不接触真实系统的路径定位、指令匹配与恢复逻辑测试（需要 JDK、Python 3 和 C 编译器）：

```sh
python3 tests/test_mount_recovery.py
python3 tests/test_patch_probe.py
python3 tests/test_semantic_patch.py
mkdir -p build/locator-tests
javac -d build/locator-tests src/dev/local/miuihdr/LibraryLocator.java tests/LibraryLocatorTest.java
java -cp build/locator-tests dev.local.miuihdr.LibraryLocatorTest
mkdir -p build/installer-tests
javac -d build/installer-tests src/dev/local/miuihdr/RootInstaller.java tests/RootInstallerTest.java
java -cp build/installer-tests dev.local.miuihdr.RootInstallerTest
```

`tests/ShellTest.java` 和 `tests/HdrInspect.java` 用于设备侧验证。带壳测试需自行提供对应的小米截图 APK 和机壳素材，仓库不分发这些文件。

停用应用 Hook：在 LSPosed 中关闭模块并重启小米截屏进程。停用底层覆盖：在 Root 管理器中禁用 `xiaomi17u_hdr_clamp_test` 并重启设备。卸载 APK 不会卸载已安装的挂载模块。

## 许可证与来源

项目源码按 [GNU GPL v3](LICENSE) 发布。第三方代码与素材保留各自许可证：

- UI 框架为 **Google / AndroidX Jetpack Compose Material 3**，设计规范为 **Material 3 Expressive**。
- 主页、安装、日志、关于页、主题及浮动导航代码改编自 **SukiSU-Ultra**（GPL-3.0）。SukiSU 是复用代码的来源，并非 UI 框架的名称；具体版本与路径见 [UI-SOURCE.txt](assets/UI-SOURCE.txt)。
- MaterialKolor：MIT。
- HDR 照片：Mishaal Rahman，CC BY 4.0，见 [素材说明](assets/HDR-PHOTO-CREDIT.txt)。
- Xposed API：Apache-2.0，见 [第三方说明](NOTICE.txt)。

本项目为独立项目，与小米、Google、SukiSU 或 LSPosed 无官方隶属关系。

This software is based in part on the work of the Independent JPEG Group.

### 预览到编辑页的过渡

放大仍使用小米原有动画。图片就位后，彩色辉光以 360 ms 渐隐，整个编辑窗口（包括背景和工具栏）按同一进度渐显；原有 HDR 预览图保持覆盖，直到编辑窗口完全显示后才退出，避免图片在过渡中闪暗。辉光始终留在原有截图排除窗口，连续截图时立即清理，退出、加载超时和系统关闭动画时回到原生流程。

应用确认/说明弹窗使用 Android 窗口背景模糊，模糊半径、遮罩和弹窗同步渐入/渐出；系统禁用窗口模糊时使用渐变遮罩。

框选/长截图裁剪导出会按原图坐标同步裁剪增益图，保留原亮度元数据；包含重新打开 JPG 后再裁剪、再次保存的真机测试。CI 同时生成 `crop-device-tests.dex`，用于在设备上调用本机小米截图裁剪实现验证。
