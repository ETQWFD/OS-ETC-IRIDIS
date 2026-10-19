<p align="center"><b>中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/wordmark-dark.svg">
    <img src="docs/assets/wordmark-light.svg" alt="IRIDIS" width="320">
  </picture>
</p>

<p align="center"><b>把大模型装进手机。</b></p>

<p align="center">聊天、智能体、语音与本地 API，推理全部在 Android 手机上完成，飞行模式下也能用。</p>

<p align="center">
  <img alt="Android 12+ arm64" src="https://img.shields.io/badge/Android-12%2B%20arm64-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <a href="CHANGELOG.md"><img alt="Version: 2.0.0-beta" src="https://img.shields.io/badge/version-2.0.0--beta-orange"></a>
</p>

<p align="center">
  <a href="https://github.com/WWRJ3321/IRIDIS/releases">下载</a> ·
  <a href="#快速上手">快速上手</a> ·
  <a href="docs/BUILD.md">从源码构建</a> ·
  <a href="docs/API.md">本地 API</a> ·
  <a href="docs/ARCHITECTURE.md">架构</a> ·
  <a href="CHANGELOG.md">更新日志</a>
</p>

<!-- 截图：放入 docs/assets/screenshots/ 后删除本注释的首尾两行即可显示。
<table align="center">
  <tr>
    <td align="center" width="25%"><img src="docs/assets/screenshots/chat.png" alt="对话" width="200"><br><sub>对话</sub></td>
    <td align="center" width="25%"><img src="docs/assets/screenshots/agent.png" alt="智能体" width="200"><br><sub>智能体</sub></td>
    <td align="center" width="25%"><img src="docs/assets/screenshots/voice.png" alt="语音" width="200"><br><sub>语音</sub></td>
    <td align="center" width="25%"><img src="docs/assets/screenshots/models.png" alt="模型管理" width="200"><br><sub>模型管理</sub></td>
  </tr>
</table>
-->

## 功能

<table>
  <tr>
    <td width="50%" valign="top">
      <b>本地对话</b><br>
      支持 Qwen3、Gemma 3、DeepSeek-R1 蒸馏等开源模型，App 内下载或导入自有文件。<br>
      深度思考可开关、可折叠，显示真实 tok/s；Markdown 渲染、编辑重发、会话搜索。<br>
      多轮对话复用 KV 前缀，只计算新增内容。
    </td>
    <td width="50%" valign="top">
      <b>智能体</b><br>
      读写与编辑工作区文件、运行 Linux 命令、联网搜索与阅读网页。<br>
      计划、技能、长期记忆与定时任务，可调用闹钟、日程等手机能力。<br>
      所有文件改动均可一键还原。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <b>离线语音</b><br>
      按住说话：SenseVoice 识别中文、粤语、英语、日语、韩语。<br>
      免提对话：边生成边朗读，开口即可打断。<br>
      多种合成音色（如 Kokoro 中英多音色），支持声音克隆。
    </td>
    <td width="50%" valign="top">
      <b>为手机调优</b><br>
      llama.cpp、LiteRT-LM、MNN 三套引擎，覆盖 CPU、GPU 与高通 NPU。<br>
      KV 缓存复用、温控降档、空闲卸载，长时间使用也不过热。<br>
      内置 OpenAI / Ollama 兼容的本地 API。
    </td>
  </tr>
</table>

<details>
<summary><b>智能体工具一览</b></summary>
<br>

在输入框旁打开「智能体」开关后，模型可以调用以下工具：

| 类别 | 能力 |
|---|---|
| 文件 | 在工作区内读、写、搜索、精确编辑文件，解析文档；改动可一键还原 |
| 终端 | 内置 Alpine Linux（PRoot，无需 root），也可接入内置 sh 或 Termux；命令按前缀设置权限 |
| 联网 | 网页搜索（必应 / DuckDuckGo / SearXNG）与网页阅读，长页面自动分段 |
| 规划与记忆 | 计划 / 待办、技能（`SKILL.md`）、长期记忆（`USER.md` / `MEMORY.md`）、历史全文检索 |
| 自动化 | 定时任务、心跳自唤醒（可限定仅充电时） |
| 手机操作 | 打开应用和链接、设闹钟、加日程、读剪贴板 |

</details>

## 快速上手

1. 从 [Releases](https://github.com/WWRJ3321/IRIDIS/releases) 下载 APK 并安装。
2. 打开「模型管理」，下载一个推荐模型。
3. 回到对话页开始聊天；需要工具时打开「智能体」。

**设备要求**：arm64、Android 12 及以上，CPU 支持 dotprod / i8mm（骁龙 8 Gen 1 及以上）。运行 4B–8B 模型建议 12 GB 内存。

> [!NOTE]
> 从 IRIS LocalAI 升级：2.1.0 起包名改为 `com.iridis`，不能覆盖旧版：先在旧版 设置›智能体›备份 导出，装新版后在 设置›存储›备份与恢复 导入。

## 推理引擎

| 引擎 | 模型格式 | 硬件 | 变体 |
|:---|:---|:---|:---|
| llama.cpp | GGUF（`.gguf`） | CPU（KleidiAI）、GPU（OpenCL） | `lite` / `full` |
| LiteRT-LM | `.litertlm` | GPU、高通 NPU | `full` |
| MNN-LLM | Taobao-MNN 模型文件夹 | CPU FP16、GPU（OpenCL） | `full` |

- 入门推荐 Qwen3-4B Q4_K_M（GGUF）；12 GB 内存的手机可尝试 8B。
- LiteRT 模型来自 `litert-community`，带芯片后缀（如 `*.sm8750`）的是 NPU 专用版本。
- NPU 加速需要骁龙 8 系芯片，并在 App 内同意高通许可后下载 QNN 运行库。
- 模型可放入 `Android/data/com.iridis/files/models/`，或在「模型管理 › 导入」中选择。

## 本地 API

在「设置 › API 服务」中开启后，手机即是一台模型服务器：

- OpenAI 兼容：`/v1/chat/completions`、`/v1/models`；Ollama 兼容：`/api/chat`。
- 默认只监听 `127.0.0.1:8686`；开放局域网需手动开启，并强制使用 API Key。

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"用一句话介绍你自己"}]}'
```

完整接口说明见 [docs/API.md](docs/API.md)。

## 隐私

对话、记忆和工作区文件只保存在手机上，并禁止云备份。App 仅在以下情况联网：

- 你主动下载模型、语音包、内置 Linux 或 NPU 运行库；
- 智能体执行网页搜索或网页阅读；
- 你开启本地 API 服务（默认仅本机可访问）。

「全部文件访问」权限只在你于「设置 › 智能体」中主动授权时使用，用于让智能体编辑 `Download/IRISLocalAI` 中由其他应用放入的文件；麦克风只在语音界面打开时使用。

## 从源码构建

需要 JDK 17 或 21、Android SDK 35、NDK r27c 及以上、CMake 3.22 及以上。

```bash
sh scripts/fetch-deps.sh                        # 拉取 llama.cpp 源码、sherpa-onnx、MNN 预编译库
cp local.properties.example local.properties    # 填写 sdk.dir（及 ndk.path）
./gradlew testFullDebugUnitTest                 # 单元测试
./gradlew assembleFullRelease                   # 三引擎版；仅 llama.cpp 用 assembleLiteRelease
```

| 变体 | 引擎 | APK 体积 |
|:---|:---|:---|
| `lite`（默认） | llama.cpp | 约 16 MB |
| `full` | llama.cpp + LiteRT-LM + MNN | 约 29 MB |

签名配置与更多细节见 [docs/BUILD.md](docs/BUILD.md)，代码结构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请确保单元测试全部通过，并在 PR 中说明改动内容与测试方式。

## 许可证

IRIDIS 以 [Apache License 2.0](LICENSE) 开源，另见 [NOTICE](NOTICE)。

第三方组件（llama.cpp、MNN、LiteRT-LM、sherpa-onnx、PRoot 等）遵循各自的许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，全文也可在 App 的「设置 › 关于」中查看。高通 QNN 运行库为专有软件，不随源码或 APK 分发。部分可选语音模型（Matcha zh-baker、ZipVoice distill、Piper xiao_ya 等）仅限非商业使用。

## 免责声明

本软件按「原样」提供，不附带任何担保。AI 生成的内容可能有误，请勿用于医疗、法律、金融等重要决策。智能体可以执行命令、修改工作区文件、打开应用和联网，请只在信任的模型和内容上开启，并留意网页或文件中夹带的提示注入。
