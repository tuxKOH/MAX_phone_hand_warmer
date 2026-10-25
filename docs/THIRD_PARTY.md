# 第三方组件与分发

本仓库不包含 Qualcomm QAIRT SDK 的头文件、预编译库或 DSP Skel 原始文件。构建 NPU 功能需自行取得并遵守对应版本的 SDK 许可。

公开的 APK 包含 QAIRT 2.48.0.260626 的 QNN HTP 运行库，仅作为本应用的组成部分分发，不提供独立 SDK 库下载。SDK 的 AI Stack License 第 1(iv) 项允许以目标代码形式随应用分发，第 1 项同时禁止将 SDK 独立分发。第三方组件不因本仓库公开而改变其许可。

APK 的 `assets/third_party/` 保留以下上游文档：

- `QAIRT_LICENSE.pdf`：Qualcomm AI Stack License。
- `QNN_NOTICE.txt`：QNN 组件的版权与第三方声明。
- `ANDROID_NDK_NOTICE.txt`：Android NDK 系统根目录提供的原生运行库声明（包含 libc++ 等相关条款）。

Gradle Wrapper 的原有 Apache-2.0 许可头保持不变，其许可全文见同目录的 `APACHE-2.0.txt`。

项目原创代码尚未指定开源许可证；公开可读不意味着取得重新许可第三方 SDK 的权利。
