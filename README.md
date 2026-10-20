<p align="center"><b>中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/icon-dark.png">
    <img src="docs/assets/icon-light.png" alt="OS-ETC-IRIDIS logo" width="112">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/wordmark-dark.svg">
    <img src="docs/assets/wordmark-light.svg" alt="OS-ETC-IRIDIS" width="320">
  </picture>
</p>

<p align="center">
  <b>一站式安卓本地 AI 平台 · OS-ETC-IRIDIS</b><br/>
  离线运行 · 数据不出手机 · 免费开源 · 32 位 / 64 位手机都能用<br/>
  <sub>All-in-one on-device AI for Android. Offline, private, open source — armv7 & arm64.</sub>
</p>

<p align="center">
  <a href="https://github.com/ETQWFD/OS-ETC-IRIDIS/releases/latest"><b>下载 APK</b></a> ·
  <a href="https://etqwfd.github.io/OS-ETC-IRIDIS/">官网</a> ·
  <a href="docs/BUILD.md">构建</a> ·
  <a href="docs/API.md">API</a> ·
  <a href="CHANGELOG.md">更新日志</a>
</p>

<p align="center">
  <img alt="Android 5+" src="https://img.shields.io/badge/Android%205%2B%20%28lite%29%20%2F%207%2B%20%28full%29-armv7%2Farm64-3DDC84?logo=android&logoColor=white"/>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"/></a>
  <a href="CHANGELOG.md"><img alt="Version" src="https://img.shields.io/badge/version-2.1.0--etc-5660F0"/></a>
</p>

<p align="center"><img src="docs/assets/cover.webp" alt="OS-ETC-IRIDIS 一站式安卓本地 AI 平台" width="720"></p>

> **OS-ETC-IRIDIS** 是 [IRIDIS](https://github.com/WWRJ3321/IRIDIS)（作者 WWRJ3321）的 ETC 分支：保留原作者的一切，新增 **armeabi-v7a（32 位）支持**，让 32 位 / 旧款 / 低配手机也能正常安装与运行（GGUF、MNN、语音、智能体终端等均可用；LiteRT-LM 官方仅有 64 位库，32 位设备会自动隐藏该引擎）。
> 有任何问题，请发送邮件给开发者：**2416444244@qq.com**

---

## 亮点

| | |
|---|---|
| **三大推理引擎** | llama.cpp · MNN · LiteRT-LM（LiteRT 仅 64 位），CPU / GPU / NPU 按机型选最快 |
| **完全离线** | 推理、语音、记录都在本机，飞行模式照常可用 |
| **智能体** | 读写文件、Linux 终端（32/64 位 proot + Alpine）、联网搜索、定时任务，改动可撤销 |
| **记忆与知识库** | 长期记忆、本地文档检索，消息可编辑、可多版本重新生成 |
| **本地语音** | 离线识别与合成，免提对话，开口即可打断 |
| **局域网 API** | OpenAI / Ollama 兼容，手机就是模型服务器 |
| **为手机调校** | KV 缓存复用、温度限速、空闲卸载 |
| **32 位支持（ETC 新增）** | armeabi-v7a 全功能编译，32 位手机可正常使用 |

## 快速开始

1. 从 [Releases](https://github.com/ETQWFD/OS-ETC-IRIDIS/releases/latest) 安装 APK
2. 在「模型管理」下载或导入模型
3. 开聊；需要工具时打开「智能体」

**要求**：Lite 版 Android 5+，Full 版 Android 7+（含 LiteRT-LM NPU，Google 库最低要求 API 24）；arm64（骁龙 8 Gen 1 及以上）或 armeabi-v7a（32 位，推荐 4B 以下模型）；4B 以上模型建议 12 GB 内存。

> [!NOTE]
> 从 IRIS LocalAI 升级：2.1.0 起包名改为 `com.iridis`，不能覆盖安装。先在旧版导出备份，装新版后在 设置 › 存储 › 备份与恢复 导入。

## 引擎

| 引擎 | 格式 | 硬件 |
|---|---|---|
| llama.cpp | `.gguf` | CPU · GPU (OpenCL)，arm64 / armeabi-v7a |
| MNN-LLM | MNN 模型文件夹 | CPU · GPU (OpenCL)，arm64 / armeabi-v7a |
| LiteRT-LM | `.litertlm` | GPU · 高通 NPU（仅 64 位设备） |

NPU 需骁龙 8 系芯片，并在 App 内同意高通许可后下载 QNN 运行库。32 位（armeabi-v7a）设备自动隐藏 LiteRT-LM 引擎（官方没有 32 位库），GGUF 与 MNN 不受影响。

## 本地 API

设置 › API 服务 中开启。默认只监听本机；开放局域网须手动开启并使用 API Key。

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}]}'
```

## 隐私

对话、记忆和文件只存于本机，禁止云备份。仅在你下载模型 / 语音包 / 运行库、智能体联网、或开启 API 时联网。

## 构建

```bash
sh scripts/fetch-deps.sh                      # llama.cpp + sherpa-onnx AAR；MNN 若缺失用 build-mnn-android.sh 编译
cp local.properties.example local.properties # 填 sdk.dir / ndk.path
./gradlew assembleFullRelease                # 仅 llama.cpp：assembleLiteRelease
```

默认同时产出 armeabi-v7a（32 位）与 arm64-v8a 原生库；JDK 17 · Android SDK 35 · NDK r27c+ · CMake。详见 [docs/BUILD.md](docs/BUILD.md)。

## 许可证

[Apache-2.0](LICENSE)。本仓库为 ETC（ETQWFD）维护的 OS-ETC-IRIDIS 分支，保留原作者 WWRJ3321 及 IRIDIS 项目一切版权与致谢。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；QNN 运行库为高通专有软件，不随源码分发；部分可选语音模型仅限非商业使用。

> AI 输出可能出错，请勿用于医疗、法律、金融等重要决策。智能体可执行命令与联网，请只对信任的内容开启。
> 有任何问题，请发送邮件给开发者：**2416444244@qq.com**
