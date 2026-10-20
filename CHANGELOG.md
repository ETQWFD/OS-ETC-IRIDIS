# 更新日志

发布版本记录见 [GitHub Releases](https://github.com/WWRJ3321/IRIDIS/releases)。本文件从开源首个版本开始记录。

## v2.1.0-etc — 2026-10-20（OS-ETC-IRIDIS 分支）

**ETC 分支首发**，基于 IRIDIS v2.1.0（原作者 WWRJ3321，版权与致谢完整保留）。

### 新增（ETC）
- 应用更名为 **OS-ETC-IRIDIS**，启用新企业风格图标（深色底 · 三棱虹彩标识）。
- **32 位支持**：默认同时构建 `armeabi-v7a` 与 `arm64-v8a`，32 位 / 旧款手机可正常安装运行。
  - llama.cpp（GGUF）与 MNN 引擎均支持 32 位；LiteRT-LM 官方仅 64 位库，32 位设备自动隐藏该引擎并给出提示。
  - `LlamaNative` 的指令集检查改为 ABI 感知（32 位不再要求 dotprod/i8mm/fp16）。
  - OpenCL 探测补全 32 位库路径（`/vendor/lib`）。
- 内置 Linux 终端支持 32 位：jniLibs 新增 armeabi-v7a 版 proot / proot-loader / talloc / android-shmem，Alpine rootfs 按 ABI 下载（aarch64 / armv7）。
- MNN 构建脚本支持双 ABI（`ANDROID_ABI=armeabi-v7a` 时关闭 ARM82/KleidiAI，改用 NEON）。
- README / NOTICE / 关于页更新为 OS-ETC-IRIDIS 品牌与支持邮箱（2416444244@qq.com）。

### 已知限制（32 位）
- LiteRT-LM（.litertlm / NPU）仅 arm64 可用；32 位建议使用 GGUF 或 MNN 模型。
- 32 位机型建议 4B 以下模型；更吃内存的模型请用 64 位设备。

## v2.1.0 — 2026-10-19

首个正式版。**包名改为 `com.iridis`，不能覆盖安装旧版**：请先在旧版 设置 › 备份 导出，安装新版后导入，确认无误再卸载旧版。

### 全新
- 工作区改为 `Download/IRIDIS`，启动时自动从旧目录迁移。
- 备份导出 / 导入（导入前确认内容；API 密钥默认不导出）。
- 消息编辑、重新生成、多版本切换（‹ n/m ›）。
- 长期记忆：默认注入聊天，支持「记住：」命令与「记住这条」。
- 简化知识库：文件放进 `Download/IRIDIS/knowledge`（txt / md / html），BM25 检索，智能体按需调用 `kb_search`，回复下显示引用。
- 浏览器搜索默认谷歌（WebView），遇验证码回退 Bing。
- 触感与提示音（设置 › 外观 › 反馈）。
- 模型共享目录 `Download/IRIDIS/models`（卸载/改包名不丢），存储页一键迁移。

### 改进
- 计划卡与工具卡中文化，回复中不再显示工具调用 JSON。
- 界面简化：回复操作栏 ⋯ 菜单，侧栏底部一行图标导航；文案 IRIS → IRIDIS。
- API：断开即取消、stop 序列、生成中不换模型、CORS / Expect / 错误返回修复。
- 图片输入修复：MNN 发图卡在预填充（计数时重复视觉编码）修复；MNN 图片缩至 512px；含图整段预填充；未读到图片时报错；预填充中停止立即生效；开启知识库时图片丢失；WebP/HEIC 转 JPEG。
- NPU（骁龙 8 Elite SM8750 / Hexagon V79）：型号匹配、原生错误直出、KV 预算、运行库完整性校验。

### 并入 2.0.1–2.0.5
- 温度限速（默认关闭）；视觉输入图片缩放到长边 1024 像素；MNN 启用 KleidiAI、8 Elite 极速档 6 线程；OpenCL 回退显示错误原因；修复 MNN GPU 总回退 CPU。

### 已知限制
- 仅支持 arm64 Android 12+；NPU 加速需骁龙 8 Gen 2 及以上并下载 QNN 运行库。
- 部分新功能（NPU 模型标识、谷歌搜索、知识库、模型目录迁移、备份迁移、触感提示音）尚待更多真机验证。

## v2.0.0-beta — 2026-10-18

首个开源公开测试版（原 IRIS LocalAI，包名 `com.iris.localai` 不变，可覆盖安装旧版）。

### 全新
- 更名 **IRIDIS**：新图标（自适应，深浅两版）与 D9 字标，系统开屏品牌图。
- 首次启动欢迎页：功能介绍 + 免责声明。
- Apache-2.0 开源；设置 › 关于 可查看许可证全文与完整开源组件列表。
- 下载高通 QNN NPU 运行库前需先同意其许可；非商用语音模型在应用内明确标注。

### 改进
- 设置 › 存储空间：总占用与分类占用条（模型、语音、工作区、终端环境、聊天记录等）。
- 智能体：工具调用更稳（误触发防护、工具名纠错、未知工具静默重试）；工具提示词精简，长对话每步 prefill 更省。
- 网页工具 SafeHttp：拒绝局域网/内网地址，防 SSRF。

### 已知限制
- 仅支持 arm64 Android 12+；NPU 加速需骁龙 8 Gen 2 及以上并下载 QNN 运行库。
- SafeHttp 不走系统代理、不解 gzip；HTTPS 主机名校验未在所有机型验证。
- 部分语音模型权重（ZipVoice、Matcha baker、Piper 小雅等）仅限非商业使用。
