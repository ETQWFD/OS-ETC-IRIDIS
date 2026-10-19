# 2.0.5-beta

## 修复
- MNN 选 GPU 总是回退 CPU：OpenCL 探测在 MNN 原生库加载之前执行，新进程里第一次加载必然报 UnsatisfiedLinkError，被当成"本机没有可用的 OpenCL"。现在先加载 libiris_mnn 再探测，GPU（OpenCL）真正可用。
