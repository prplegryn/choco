# Choco

Choco 是一款 Android 聊天与 agent 应用。连接自己的模型服务，在对话中使用文件、Linux 命令和其他工具完成任务。

应用使用 Kotlin 和 Jetpack Compose 开发，支持 Android 10 及以上的 arm64 设备。

## 能做什么

接入兼容 OpenAI Chat Completions、Responses 和 Anthropic Messages 的服务；按供应商管理连接、模型和生成参数。支持流式回复、Markdown、代码和 LaTeX 公式，也可以使用单独配置的图像模型生成或编辑图片。

在对话中处理文件、运行 Python 和 shell 命令，使用 Git、SSH 和 HTTP。每个会话有独立的 Alpine Linux 用户空间和工作区；长时间运行的进程可以在切换会话后继续运行。Linux 环境随应用提供，不需要另装终端。

笔记本使用 Markdown 保存，记忆可按全局或会话范围管理。支持 MCP、Skills、会话分支和定时任务。工具和凭据的使用由用户授权；定时任务独立保存模型与权限。

## 开始使用

安装应用后，在设置中添加模型供应商，填写 API 地址和凭据，再获取或手动添加模型。项目不包含模型服务的 API key。图片生成需要另行配置支持图片接口的模型。

当前仓库的 `runtime-v1` Release 仅提供构建所需的 Linux 运行时，并不是可安装的 APK。可以按下面的步骤从源码构建调试包。

## 从源码构建

需要 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0。项目使用 Gradle 8.13，Gradle Wrapper 会下载对应版本。

先从 [runtime-v1](https://github.com/prplegryn/choco/releases/tag/runtime-v1) 下载 `runtime-arm64-v1.zip`，然后在仓库根目录执行：

```sh
python3 tools/runtime_bundle.py verify /path/to/runtime-arm64-v1.zip
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

第一条命令会校验运行时包的 SHA-256 并还原构建所需文件。调试 APK 位于 `app/build/outputs/apk/debug/`。也可通过 `tools/prepare_runtime.py` 从上游组件准备运行时；来源和校验值见 `runtime-lock.json`。

Release 构建需要自行提供签名文件及 `CHOCOLA_KEYSTORE_PATH`、`CHOCOLA_SIGNING_PASSWORD`。要覆盖安装已有版本，还需要使用相同的签名证书，并确保版本号高于已安装版本；调试包不能直接覆盖不同证书签名的安装包。

## 数据与权限

会话、设置和记忆保存在设备上，敏感数据使用 Android Keystore 保护。诊断日志不会记录聊天正文或请求 body，但可能包含经过过滤的命令、工具参数摘要和输出片段；分享日志前请自行检查。

Linux 用户空间使用 PRoot，不是虚拟机，也不是防范恶意代码的安全边界。应用没有系统 root 权限，文件访问仍受 Android 权限限制。后台进程与定时任务受系统电池策略、网络和设备状态影响，不能保证始终运行或准时触发。

## 第三方组件

运行时组件的来源和校验信息见 `runtime-lock.json`，相关源码随运行时 Release 提供。图标和内置第三方组件的许可文件保留在 `design/icons/` 和 `app/src/main/assets/`。项目自身目前未声明开源许可证；公开源码不等同于授予再分发许可。
