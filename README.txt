小米截屏HDR增强 / MI Screen HDR Enhanced 1.5.2

正式下载
https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/releases/tag/v1.5.2

使用要求
Android 14 或更新版本、ARM64、小米原生截屏、Root，以及支持 API 102 的 LSPosed。
在 LSPosed 中启用本模块，作用域选择“小米截屏”（com.miui.screenshot）。

安装底层修复
打开应用主页的安装入口，选择“自动识别并安装”，点击“安装”。
应用会生成本机挂载模块，并调用现有 Magisk、KernelSU（含 SukiSU）或 APatch 安装器刷入。
沿用现有挂载方式；安装完成后手动重启设备以加载。

选择文件导出
选择“选择 SO 文件并导出”，读取对应设备的原始 SO，点击“修补并导出”。
只生成 ZIP，不与当前手机的库比较，不刷入当前设备。
ZIP 保存在 Download/MI-Screen-HDR-Enhanced/，在原始库对应的设备及系统版本上通过 Root 管理器安装。
安装包会在目标设备查找并验证对应运行库，再确定挂载位置。

日志与反馈
七个中文阶段显示安装进度；管理器刷入日志原样显示。
完整日志自动保存到 Download/MI-Screen-HDR-Enhanced/logs/。
反馈入口：https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced/issues

停用
在 LSPosed 中停用模块，再重新加载系统截屏，可关闭截图增强。
底层挂载模块需在 Root 管理器中另行禁用或卸载，并重启设备。
卸载应用不会自动卸载已有挂载模块。

项目说明
功能、安装和兼容范围见 README.md；构建、实现说明见 DEVELOPMENT.md。
源码按 GPL-3.0 发布，第三方来源及各自许可见 NOTICE.txt、LICENSE、assets/licenses 和 assets/UI-SOURCE.txt。
This software is based in part on the work of the Independent JPEG Group.
