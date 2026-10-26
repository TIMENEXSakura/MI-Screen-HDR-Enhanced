# 小米截屏HDR增强

**MI Screen HDR Enhanced · 1.5.9**

为小米系统截屏保留 HDR，从截图预览、裁切编辑到带壳保存，让高光与亮度信息随图片一起保留。

[下载 1.5.9 APK](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/releases/download/v1.5.9/MI-Screen-HDR-Enhanced-1.5.9.apk) · [正式版说明](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/releases/tag/v1.5.9) · [反馈问题](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/issues)

## 主要功能

- **HDR 截图与预览**：增强小米系统截屏的 HDR 捕获，改善缩略图、编辑页与带壳预览的 HDR 显示，减少进入编辑页时的闪烁。
- **编辑后保留 HDR**：修复裁切、再次保存和带壳合成中的 HDR 信息丢失。
- **带壳格式切换**：点按原有带壳按钮，依次切换 JPG、透明 PNG HDR、关闭带壳。
- **流动辉光**：彩色光带跟随截图轮廓移动和缩放；进入编辑页时辉光渐隐、裁切框渐显。辉光、辉光 HDR 亮度和 HDR 标记可分别开关。
- **亮度比例调整**：保留自动模式，也可在当前比例上加减或强制指定比例；支持直接输入和加减按钮，从下一次截图生效。
- **像素增益优化**：可选优化 HDR 区域并提高 JPG 保存质量，默认关闭；开启会增加处理开销和文件体积。
- **HDR 测试与管理**：内置测试照片和亮度对照；主页可重新加载系统截屏，执行前会确认并提醒保存编辑。
- **清楚的安装进度**：七个中文阶段、进度百分比与管理器原始刷入日志，完整记录自动保存，便于反馈问题。
- **RGB 保留与分级回退**：保留通道增益和高质量 JPEG；高精度取帧中 RGB 失败先尝试普通精准读取，两者都失败才回退系统截图。
- **Ultra HDR 开关检查**：挂载包安装时确认 `persist.sys.support_ultra_hdr=true`，缺失或未开启时写入并验证。导出 ZIP 本身不改动系统属性。

## 实验性功能

- **底层 RGB 算法**：仅对完整库指纹已经验证的实现修补增益生成；不支持的库会明确报告。
- **特殊 HDR 取帧**：可选“匹配屏幕颜色”或“兼容取帧”，尝试从同一张高精度画面重建增益。默认仍使用系统取帧，与 RGB 保留开关独立。
- **录屏与合成后端**：提供录屏后端、帧等待及超时继续录制设置；Vulkan 及系统合成后端切换属于实验功能，后者需手动重启。

实验功能不代表完整支持所有杜比、徕卡或其他私有 HDR 路径，具体范围见 [1.5.9 更新说明](RELEASE_NOTES-1.5.9.txt)。

## 使用条件

- 使用小米系统截屏的 Android 14 及以上、ARM64 设备。
- 设备支持Google Ultra HDR特性。
- 已获取 Root，使用 Magisk、KernelSU（含 SukiSU）或 APatch。
- 已安装支持 **API 102** 的 **LSPosed** 实现。

已在小米 17 Ultra（SukiSU + Mountify）上验证 HDR 截图与安装流程。部分流媒体平台的杜比视界内容仍可能出现增益图缺失、偏弱或异常，后续会继续改善。

不同机型、固件和小米截屏版本的支持情况可能不同；无法确认兼容时会报告失败并保留原库。亮度调整无法补回应用未提供的 HDR 高光数据；透明 PNG HDR 的显示效果也取决于查看软件。

## 安装与使用

1. 安装上方 APK，在 LSPosed 中启用模块，作用域选择 **小米截屏（`com.miui.screenshot`）**。
2. 打开应用，点按**主页「截屏增强」状态卡 → 安装**。
3. 选择**自动识别并安装**，点「安装」。需要为其他设备准备模块时，选择**选择 SO 文件并导出**，再点「修补并导出」。
4. 授予 Root 权限。自动模式会生成并刷入本机模块；选择文件只导出 ZIP，由你在对应设备的 Root 管理器中安装。刷入完成后**手动重启手机**以加载底层修复。
5. 在「增强设置」调整辉光、HDR 比例等选项，通过「HDR 测试」检查效果。

需要录屏实验功能时，在 LSPosed 作用域同时选择小米屏幕录制（`com.miui.screenrecorder`）。升级后请重新执行 APP 的底层模块安装；仅更新 APK 不会替换已生成的挂载包。Ultra HDR 开关写入持久属性，停用或卸载模块不会自动撤销。

应用沿用现有的 Magic Mount、OverlayFS、Mountify 或兼容元模块，不替换已有挂载配置。Root 环境须先具备可用的挂载方式；Mountify 手动选择模式需要选中本模块。不同组合的实际支持以安装及启动结果为准，安装成功并不等于已经加载。

选择文件导出的 ZIP 保存在 `Download/MI-Screen-HDR-Enhanced/`，生成时不会安装或更改本机挂载配置。请在该原始库对应的设备及系统版本上安装，安装时会检查是否存在对应库。自动刷入失败时也会保留生成包。应用不会自动重启设备。

## 日志与反馈

安装或导出完成后，无论成功或失败，完整日志自动保存到：

```text
Download/MI-Screen-HDR-Enhanced/logs/
```

也可在安装记录页右上角手动保存。提交 [Issue](https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/issues) 时，请附设备型号、系统与小米截屏版本、Root / LSPosed 版本、复现步骤及相关日志。

## 许可证与来源

源码采用 [GPL-3.0](LICENSE)。界面框架为 **Google / AndroidX Jetpack Compose Material 3**，设计规范为 **Material 3 Expressive**；主页、安装、日志、关于页等界面代码改编自 **SukiSU-Ultra**，来源见 [UI-SOURCE.txt](assets/UI-SOURCE.txt)。第三方代码和 HDR 素材保留各自许可，详见 [NOTICE.txt](NOTICE.txt)。

本项目独立维护，与小米、Google、SukiSU 或 LSPosed 无官方隶属关系。

[开发文档与兼容性细节](DEVELOPMENT.md) · [1.5.9 更新说明](RELEASE_NOTES-1.5.9.txt) · [图标 SVG](artwork/mi-screen-hdr-enhanced.svg)
