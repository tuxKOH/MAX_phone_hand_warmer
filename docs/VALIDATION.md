# 验证范围

## v0.2.1

- 新增 Snapdragon 8 Gen 2 / SM8550（HTP v73），包含同一 QAIRT 版本的 v73 Stub、CalculatorStub 和 DSP Skel。
- 打包时对 DSP Skel 禁用符号裁剪，并逐字节核对 APK 内的 v73/v75/v79/v81 Skel 与 SDK 原文件一致。
- QAIRT 2.48.0.260626 的支持表明确列出 SD 8 Gen 2 / SM8550 为 HTP v73。
- 根据运行库报告的 HTP 架构，v73 跳过要求 v75 及以后的独立 HMX/CENG 性能请求，保留 DSP/总线请求。
- 主机端机型识别检查覆盖 SM8550、大小写和 AB/AC 后缀、非 ARM64、非支持芯片与原有平台回归；不施加计算负载。
- Java / ARM64 / x86_64 及测试 APK 构建通过，Android Lint 为 0 errors、5 warnings；发布 APK 已核对版本号、v73 库、第三方声明、签名和 CPU 侧 ELF 的 16 KB 对齐。
- 没有 8 Gen 2 真机执行或性能结果；仅完成构建、识别与打包检查，不能承诺厂商固件兼容性或满载。

识别检查可在主机执行，无需连接手机（`ANDROID_HOME` 指向 Android SDK）：

```bash
test_classes=$(mktemp -d)
javac -encoding UTF-8 -cp "$ANDROID_HOME/platforms/android-36/android.jar" -d "$test_classes" app/src/main/java/dev/maxheater/DeviceProfile.java app/src/test/java/dev/maxheater/DeviceProfileTest.java
java -cp "$test_classes:$ANDROID_HOME/platforms/android-36/android.jar" dev.maxheater.DeviceProfileTest
```

## v0.2.0

- Java / ARM64 / x86_64 构建通过；Android Lint 为 0 errors、5 warnings。
- 入口确认页在模拟器通过检查：默认未勾选、未确认无法进入、退出有效、确认后进入主界面，未提交压力负载。
- 模拟器通过 CPU、吞吐换算、安全条件、停止和会话所有权检查。该环境只声明 GLES 3.0，GPU 和 HTP 检查明确跳过。
- 较早版本曾在 Snapdragon 8 Elite / SM8750 上完成 CPU、GPU 和真实 QNN HTP 图执行。
- v0.2.0 的 HTP 进程内缓存重构只做构建与代码检查；按设备所有者要求停止实机压力测试，未再次验证缓存的真机运行。
- CPU 侧打包原生库的 ELF LOAD 段已检查至少 16 KB 对齐；Hexagon DSP Skel 不属于手机 CPU 侧 ELF。

没有可据此证明当前版本达到 NPU 官方峰值或 GPU 持续 100% 利用率的测量。低电量、失去前台或没有进入采样窗口的短测不计为有效性能结果。

真机截图、ADB 标识和原始调试日志未公开。README 提及的约 45 W 是设备使用者报告的整机观察值，未经本项目独立核验。
