# 逐像素增益图与 JPG 4:4:4 实验

这里保留独立设备侧原型和复现工具。1.4 开发版将同类算法与原生 JPEG 4:4:4 编码器接入默认关闭的可选开关，随 1.4.5 正式发布；生产代码在 src/ 和 native/。1.4.5 的精确查表及编码提速不改变下述原型的计算结果，见 ../../tests/VALIDATION-v1.4.5.txt。
本实验不安装挂载模块、不改系统库、不重启 SurfaceFlinger。

## 做法及取舍

当前设备的系统增益图已经是全分辨率。大面积非零值还包含局部色调映射的补偿，不能直接当作高光外溢，更不能直接清零。

原型按 Android 的 [Gainmap 重建公式](https://developer.android.com/reference/android/graphics/Gainmap)，在原色彩空间中逐像素恢复完整 HDR 线性 RGB。普通区域的补偿移回 SDR 底图；明确超过 SDR 白色的区域，按最大通道归一化底图，并重新编码剩余增益。整个过程无空间模糊、膨胀或邻域扩散。

代价：SDR 底图和中间 HDR 显示亮度下的观感会变化。保留的是完整 HDR 重建值，仍有 8 位量化误差。接近 SDR 白色的弱高光存在判断不确定性；枚举测试中，最高约 1.01041 倍白色的输入可能被归入普通区域。不能从已量化的截图恢复原始场景的绝对真值。

仅测试不透明、全分辨率、sRGB / Display P3、SDR 底图的截图。低分辨率增益图、透明底图、不同替代色域和反向增益图不属于本原型支持范围。未验证其他手机、视频平台、编辑和机壳链路；不应直接替换生产算法。

## 真机结果

设备：Xiaomi 17 Ultra，Android 17 / API 37。真实捕获使用 `fixtures/capture.png`，已知测试源为 `fixtures/source.png`；均为程序生成的图案，不含用户照片。`PatternActivity.java` 保留了原图生成方式。

| 检查项 | 结果 |
| --- | --- |
| 原测试图 HDR 像素 | 5,881 |
| 系统截图非零增益像素 | 1,994,643 |
| 重算 PNG 非零增益像素 | 5,881，误增和漏增均为 0 |
| PNG 高光通道相对系统捕获最大变化 | 0.07524% |
| 1200 × 2608 原型处理耗时 | 单次约 261 ms；不是性能统计 |
| 8 组彩色、A8/RGB、不同 gamma/偏移/增益范围测试 | 每组 516 个高光像素，PNG 无误增、漏增 |
| 同一结果重复算法 10 次 | 最大通道漂移约 0.000008%；不是重复显示再截屏测试 |

严格逐像素结果仅指这些已知测试图及其像素无损保存，不能推广为所有图片、显示器或截图源都无外扩。

## PNG 转 JPG，同样像素排布

能保持相同尺寸、坐标、完整分辨率增益图和 HDR 元数据；无法用常规有损 JPG 保证所有数值相同。

本机 Android `Bitmap.compress(JPEG, 100)` 实测底图和增益图均为 4:2:0：虽然两个文件的像素尺寸未变，颜色采样仍被减半。量化表已经全为 1，说明只继续提高质量滑块解决不了颜色采样问题。

`jpeg444.py` 从 Android **已保存并重新解码的 PNG** 像素出发，分别以质量 100、4:4:4 编码底图和增益图，保留匹配的 Android JPEG 的 ICC、ISO 21496-1、XMP 元数据，并重新计算 MPF 位置、长度及 XMP 辅图长度。它只接受本实验自己生成的匹配文件，不是通用 JPEG 编辑器。另附 RGB JPEG 对照，未显示稳定优势，兼容性也未全面验证。

| 1 像素彩色线测试，高光相对 PNG 的最大亮度误差 | 结果 |
| --- | --- |
| Android JPG，质量 100，4:2:0 | 24.68535% |
| 实验 JPG，质量 100，4:4:4 | 0.92866% |

这是刻意使用的细线压力测试，不代表普通照片的平均误差。4:4:4 文件已由 Android 解码器验证为 Display P3 + Gainmap HDR，未宣称所有图库都兼容。

中性测试图即使 4:4:4，仍有 322 个原本为零的增益像素变成 1/255；已知 5,881 个高光均保留，普通区域没有超过 1.01 倍白色。非零增益不等于可见发光。JPEG 量化和取整仍在，所以不能称为无损。参见 [libjpeg-turbo 编码说明](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/doc/usage.txt) 和 [Ultra HDR 格式](https://developer.android.com/media/platform/hdr-image-format)。

## 复现

构建需要 JDK 17、Android SDK 37 和 Android NDK；在本机 Termux/PRoot 可使用脚本中的 clang 后备路径。不会调用正式 APK 的构建脚本。

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
# SDK 目录名称不同时，设置 HDR_PLATFORM_JAR；D8 路径可设 HDR_D8_JAR。
bash build.sh
```

将 `out/libhdrprecision.so`、`out/dex/classes.dex` 和 fixtures 放到 Android shell 可读取的目录。以下在设备的系统 shell 执行（路径按实际调整）：

```sh
HDR_EXP=/data/local/tmp/hdr-precision
mkdir -p "$HDR_EXP/results"
export CLASSPATH="$HDR_EXP/classes.dex"
app_process /system/bin dev.local.miuihdr.PrecisionProbe \
  "$HDR_EXP/libhdrprecision.so" "$HDR_EXP/capture.png" \
  "$HDR_EXP/results/rebased" "$HDR_EXP/source.png"
app_process /system/bin dev.local.miuihdr.PrecisionCases \
  "$HDR_EXP/libhdrprecision.so" "$HDR_EXP/results"
```

把结果目录复制回有 Python、Pillow、NumPy 的环境：

```sh
python3 jpeg444.py /path/to/results
```

将新生成的 `*-444.jpg`、`*-rgb444.jpg` 放回设备的同一结果目录，再运行：

```sh
app_process /system/bin dev.local.miuihdr.PrecisionDecode "$HDR_EXP/results"
```

取回解码生成的 `.base/.map/.json`，运行独立全图审核：

```sh
python3 analyze.py /path/to/results
```

PNG 像素往返完全一致，但 Android 的 HDR 元数据序列化也会有微小量化，审核允许 0.02% 的相对元数据误差。报告以完整 HDR 线性值比较，不用 SDR 缩略图推测亮度。原始 dump 较大，仓库只保留代码、两个小型已知输入及 JSON 测量结果。未采用系统二进制或新的底层补丁。
