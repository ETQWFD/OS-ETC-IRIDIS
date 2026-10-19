# 2.0.2-beta

## 修复
- 发图片给视觉模型时长时间卡在「读取提示（预填充）」：之前把原图直接交给引擎，手机截图（如 1080×2400）会被切成几千个图块，MNN 视觉编码器在 CPU 上要跑好几分钟。现在发送前生成一份长边不超过 1024 像素的副本（按 EXIF 方向转正，JPEG 90），缓存在原图旁（`<原文件>.vis.jpg`），删附件时一起删除；任何失败都退回原图。三种引擎通用。

## 改动文件
- util/io/Attachments.kt：`VISION_MAX_SIDE`、`visionTarget`、`visionPathFor`、`visionCopy`；`delete` 同时删缓存。
- ui/chat/ChatViewModel.kt：`withImages` 改为 suspend，在 IO 线程取缩放副本。
- 测试 V202VisionScaleTest。
- versionCode 174，versionName 2.0.2-beta。
