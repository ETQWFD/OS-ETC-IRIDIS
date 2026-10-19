package com.iris.localai.ui.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo

/** Smooth "follow the newest text" scrolling for the chat list (step is unit-tested). */
object FollowScroll {
    /** Pixels from the end of the last item to the bottom of the viewport; null when the last item is not visible. */
    fun distanceToEnd(info: LazyListLayoutInfo): Float? {
        val last = info.visibleItemsInfo.lastOrNull() ?: return null
        if (last.index != info.totalItemsCount - 1) return null
        val end = last.offset + last.size
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        return (end - viewportEnd).toFloat().coerceAtLeast(0f)
    }

    /**
     * How far to scroll this frame: 22 % of the remaining distance (an exponential glide that
     * lands in ~150 ms), at least 1 px so it always arrives, and everything at once with reduce-motion.
     */
    fun step(distance: Float, reduceMotion: Boolean): Float =
        if (reduceMotion || distance <= 1f) distance else maxOf(1f, distance * 0.22f)
}
