# 录屏后端测试 0.1

独立测试应用，可切换 **GLES 3 / Vulkan** 录屏，用于比较游戏中的录屏开销。与小米截屏 HDR 增强使用不同包名，可以同时安装。

- 720 / 1080 短边、30 / 60 FPS 上限，横屏或竖屏，30 / 60 / 120 秒自动停止。
- 视频保存到 `Movies/MI-Recorder-Test`，诊断报告保存到 `Download/MI-Recorder-Test`。
- 首次录屏需要 Android 屏幕捕获授权；可以从通知中停止录制。
- Vulkan 不可用时直接报告失败，不会自动回退到 GLES。

## 对比方法

1. 在应用里选择与游戏一致的画面方向，固定分辨率、帧率和时长。
2. 先用 GLES 录制，再用 Vulkan 录制相同游戏场景。两轮之间等待手机温度接近。
3. 对比游戏流畅度、录制文件是否正常，以及两份报告。报告中的编码帧率不是游戏帧率；静止画面可能没有持续新帧。
4. 出错时保留对应报告。请特别检查画面方向、色彩、视频时长和有无花屏。

## 本轮范围

只测试 **无声、8 位 SDR、AVC 硬件编码**。它不是 HDR 录屏修复包，也不验证音频同步。系统的画面合成和硬件视频编码仍由 Android 执行。

两种模式使用同一虚拟显示输入、同一编码器选择规则和参数。应用内 Vulkan 路径直接导入捕获的硬件缓冲区，以 Vulkan 完成 GPU 图像复制并提交到编码器 Surface，不经过应用内 EGL/GLES；GLES 路径导入同一类缓冲区并完成一次纹理绘制。

录屏本身不请求 Root、不修改系统库。应用中另提供可选的系统底层 GLES / Vulkan 选择，需要 Root 和配套启动模块，重启后生效。此模块不包含任何 SO，不重复挂载已有 HDR 修复库。与小米录屏相比，仍有整个应用实现的差异，不能仅凭测试结果断言“换 API 一定更快”。

## 构建与可验证范围

GitHub Actions 构建 APK、执行帧率与时间戳单元测试、Android Lint 和原生打包检查。构建成功不代表某台手机的 Vulkan 编码器 Surface 一定兼容，须通过真机录屏确认。

本应用使用独立渲染线程与编码输出线程，最多保留两帧 GPU 任务；捕获缓冲区等到 GPU 完成后才释放。报告中的 `submit_and_wait_ms` 包含 GPU 提交和队列等待，是渲染线程耗时，不是 GPU 时间或整个系统负载。未使用 CPU 读回像素。

Vulkan 路径需要 Vulkan 1.1、Android HardwareBuffer 导入、foreign queue、编码器 Surface 的交换链与传输目标支持，以及用于传递捕获时间戳的 `VK_GOOGLE_display_timing`。初始化和运行失败都保留具体错误。

参考：[Android MediaProjection](https://developer.android.com/media/grow/media-projection)、[NDK ImageReader](https://developer.android.com/ndk/reference/group/media)、[Vulkan AHardwareBuffer](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)、[Android Vulkan 编码表面时间戳实现](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/swapchain.cpp)。

许可：沿用仓库 GPL-3.0；本目录不包含小米系统软件或系统库。

## 系统底层联合测试

APK 同时提供配套模块导出。点按“导出底层测试模块 ZIP”，到现有 Magisk / KernelSU / APatch 管理器安装，再返回读取底层状态。无需安装另一种挂载引擎。选择底层后端并保存后，手动重启手机，等约 30 秒，再检查实际后端及本次开机通讯状态。

先以系统默认后端分别测试应用的 GLES 和 Vulkan；再切换底层，重复同样两轮录屏。这样可比较四种组合。底层选择会影响整个系统，不能视为仅作用于录屏。需恢复时选择“系统默认”并重启，或在 Root 管理器停用配套测试模块后重启。

模块通过协议端点、安装标识、请求随机值和当前开机回报验证状态，不依赖模块显示名称。初次安装默认不覆盖系统配置。未完成上次启动、显示进程连续重启或实际后端未通过校验时，会取消后端覆盖；无法进入系统时可使用 Root 管理器原有安全模式停用测试模块。不会自动重启手机或杀死 SurfaceFlinger。

系统后端支持检查是库能力预检，最终以 `dumpsys SurfaceFlinger` 的实际输出为准；不支持时不做 SO 指令修补。未知第三方脚本可能设置同一系统属性，建议测试前停用其他渲染后端调整工具。
