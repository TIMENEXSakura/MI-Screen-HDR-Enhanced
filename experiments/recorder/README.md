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

应用不请求 Root、不修改系统库、不切换 SurfaceFlinger 后端、不安装挂载模块。与小米录屏相比，仍有整个应用实现的差异，不能仅凭测试结果断言“换 API 一定更快”。

## 构建与可验证范围

GitHub Actions 构建 APK、执行帧率与时间戳单元测试、Android Lint 和原生打包检查。构建成功不代表某台手机的 Vulkan 编码器 Surface 一定兼容，须通过真机录屏确认。

本应用使用独立渲染线程与编码输出线程，最多保留两帧 GPU 任务；捕获缓冲区等到 GPU 完成后才释放。报告中的 `submit_and_wait_ms` 包含 GPU 提交和队列等待，是渲染线程耗时，不是 GPU 时间或整个系统负载。未使用 CPU 读回像素。

Vulkan 路径需要 Vulkan 1.1、Android HardwareBuffer 导入、foreign queue、编码器 Surface 的交换链与传输目标支持，以及用于传递捕获时间戳的 `VK_GOOGLE_display_timing`。初始化和运行失败都保留具体错误。

参考：[Android MediaProjection](https://developer.android.com/media/grow/media-projection)、[NDK ImageReader](https://developer.android.com/ndk/reference/group/media)、[Vulkan AHardwareBuffer](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)、[Android Vulkan 编码表面时间戳实现](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/swapchain.cpp)。

许可：沿用仓库 GPL-3.0；本目录不包含小米系统软件或系统库。
