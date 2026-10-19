package com.iris.localai.engine

/** 1.10.9: texts for 「取消加载」. */
object LoadCancel {
    const val NOTICE = "已取消加载。底层加载这一步无法中途打断，结束后会立即释放内存；期间再加载会排队等待。"
    const val RELEASED = "已释放取消加载的模型"
}
