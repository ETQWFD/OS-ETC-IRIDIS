package com.iris.localai.util

/** 1.10.13: how hard 停止 tries before it ends the request itself (≈1.5 s). */
object StopPolicy {
    const val RETRIES = 5
    const val RETRY_MS = 300L
}
