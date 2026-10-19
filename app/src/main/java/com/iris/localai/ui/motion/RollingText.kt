package com.iris.localai.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.iris.localai.ui.theme.LocalReduceMotion

/** Splits text into runs: digits animate one by one (odometer), everything else is static text. Unit-tested. */
object RollingSegments {
    sealed interface Segment
    data class Static(val text: String) : Segment
    data class Digit(val digit: Char, val slot: Int) : Segment

    /**
     * Digits get a slot counted from the *end* of their number, so 9 → 10 keeps the ones digit in
     * the same slot (and only the new tens digit appears) instead of every digit shifting.
     */
    fun split(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val sb = StringBuilder()
        var i = 0
        var numberIndex = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                if (sb.isNotEmpty()) { out += Static(sb.toString()); sb.clear() }
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                val len = j - i
                for (k in i until j) out += Digit(text[k], numberIndex * 100 + (len - 1 - (k - i)))
                numberIndex++
                i = j
            } else {
                sb.append(ch); i++
            }
        }
        if (sb.isNotEmpty()) out += Static(sb.toString())
        return out
    }

    /** Roll direction for one digit slot: up when the digit grew (9 → 0 counts as growing). */
    fun rollsUp(old: Char, new: Char): Boolean = if (old == '9' && new == '0') true else if (old == '0' && new == '9') false else new >= old
}

/**
 * Text whose digits roll like an odometer when they change (counters, seconds, percentages,
 * speeds). Non-digit text is plain. Each digit is its own tiny AnimatedContent, so a change
 * recomposes only that digit. Instant when motion is reduced.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    if (reduce) {
        Text(text, style = style, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RollingSegments.split(text).forEachIndexed { index, seg ->
            when (seg) {
                is RollingSegments.Static -> key("s$index") { Text(seg.text, style = style, color = color, maxLines = 1, softWrap = false) }
                is RollingSegments.Digit -> key("d${seg.slot}") {
                    AnimatedContent(
                        targetState = seg.digit,
                        transitionSpec = { IrisMotion.roll(RollingSegments.rollsUp(initialState, targetState)) },
                        label = "digit",
                    ) { d -> Text(d.toString(), style = style, color = color, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
