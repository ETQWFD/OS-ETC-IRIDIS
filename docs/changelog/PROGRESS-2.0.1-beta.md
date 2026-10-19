# IRIDIS 2.0.1 Beta（versionCode 172 → 173）

## 新增：温度限速
- 设置 › 性能与资源 › 功耗：「温度限速」开关 + 「限速温度」35–50°C 滑块（默认关闭，开启默认 42°C）。
- 按电池温度判断；达到设定温度后在解码线程里按词元间隔休眠，真正降低芯片负载：超出 0–1°C 约 10 字/秒，1–2°C 6，2–3°C 4，≥3°C 2；降到设定以下 1°C 恢复全速。
- 对 llama.cpp、MNN、LiteRT 三个引擎都生效；停止生成仍然即时。
- 新设置字段 thermalLimitC（默认 0 = 关闭）；V178Test 已更新；新增 V201ThermalTest。
- 代码：engine/resources/ThermalGovernor.kt；温度来自 ACTION_BATTERY_CHANGED（IrisApp）。

## 语音识别语言
- 设置 › 语音 › 识别语言去掉日语、韩语（只留 自动 / 普通话 / 粤语 / 英语），修复「普通话」显示不全；之前选了日语/韩语的改回自动。
