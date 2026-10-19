# IRIDIS 2.0.0 Beta（versionCode 172）

首个公开测试版（原 IRIS LocalAI 1.14.7 内容），开源发布。

## 本版
- 版本号改为 2.0.0-beta（versionCode 172），作为首个开源公开版本。
- 版本测试改为兼容 2.x 版本号。

## 自 IRIS LocalAI 以来的主要内容（1.14.x）
- 更名 IRIDIS：新图标（自适应、深浅两版）、D9 字标、系统开屏品牌图。
- 首次启动欢迎页（功能介绍 + 免责声明）。
- 项目许可证 Apache-2.0；根目录 LICENSE / NOTICE / THIRD_PARTY_NOTICES.md。
- 设置 › 关于：许可证全文、完整开源组件列表、如实的网络使用说明。
- 下载高通 QNN NPU 运行库前需同意许可；非商用语音模型标注。
- 设置 › 存储空间；机箱抽屉读数；网页工具 SafeHttp 防局域网访问。

## 已知限制
- 仅 arm64；NPU 加速需骁龙 8 Gen 2 及以上并下载 QNN 运行库。
- SafeHttp 不走系统代理；HTTPS 主机名校验未在所有机型验证。
