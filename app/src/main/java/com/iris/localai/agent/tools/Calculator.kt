package com.iris.localai.agent

import kotlin.math.PI
import kotlin.math.E

class CalcException(message: String) : Exception(message)

/**
 * 1.9.0: safe arithmetic for the calculator tool. A recursive-descent parser over numbers,
 * + − × ÷ % ^ (also ** and the Chinese ×÷ signs), parentheses, unary signs, constants pi / e and
 * a fixed whitelist of functions. Nothing is ever evaluated as code. Pure, unit-tested.
 */
object Calculator {
    private const val MAX_LEN = 500
    private const val MAX_DEPTH = 64

    private val FUNCS: Map<String, (List<Double>) -> Double> = mapOf(
        "sqrt" to { a -> one(a, "sqrt") { kotlin.math.sqrt(it) } },
        "abs" to { a -> one(a, "abs") { kotlin.math.abs(it) } },
        "sin" to { a -> one(a, "sin") { kotlin.math.sin(it) } },
        "cos" to { a -> one(a, "cos") { kotlin.math.cos(it) } },
        "tan" to { a -> one(a, "tan") { kotlin.math.tan(it) } },
        "asin" to { a -> one(a, "asin") { kotlin.math.asin(it) } },
        "acos" to { a -> one(a, "acos") { kotlin.math.acos(it) } },
        "atan" to { a -> one(a, "atan") { kotlin.math.atan(it) } },
        "ln" to { a -> one(a, "ln") { kotlin.math.ln(it) } },
        "log" to { a -> if (a.size == 2) kotlin.math.ln(a[0]) / kotlin.math.ln(a[1]) else one(a, "log") { kotlin.math.log10(it) } },
        "log10" to { a -> one(a, "log10") { kotlin.math.log10(it) } },
        "log2" to { a -> one(a, "log2") { kotlin.math.log2(it) } },
        "exp" to { a -> one(a, "exp") { kotlin.math.exp(it) } },
        "floor" to { a -> one(a, "floor") { kotlin.math.floor(it) } },
        "ceil" to { a -> one(a, "ceil") { kotlin.math.ceil(it) } },
        "round" to { a ->
            if (a.size == 2) { val f = Math.pow(10.0, a[1]); Math.round(a[0] * f) / f } else one(a, "round") { Math.round(it).toDouble() }
        },
        "min" to { a -> if (a.isEmpty()) throw CalcException("min 至少需要一个参数") else a.min() },
        "max" to { a -> if (a.isEmpty()) throw CalcException("max 至少需要一个参数") else a.max() },
        "pow" to { a -> if (a.size != 2) throw CalcException("pow 需要两个参数") else Math.pow(a[0], a[1]) },
    )

    private fun one(a: List<Double>, name: String, f: (Double) -> Double): Double {
        if (a.size != 1) throw CalcException("$name 需要一个参数")
        return f(a[0])
    }

    fun evaluate(expression: String): Double {
        val src = expression.trim()
        if (src.isEmpty()) throw CalcException("表达式为空")
        if (src.length > MAX_LEN) throw CalcException("表达式过长")
        val p = P(normalize(src))
        val v = p.expr(0)
        p.skipWs()
        if (!p.end()) throw CalcException("无法识别的字符：'${p.peekChar()}'（位置 ${p.pos + 1}）")
        if (v.isNaN()) throw CalcException("结果不是数字（例如负数开平方）")
        if (v.isInfinite()) throw CalcException("结果为无穷大（例如除以 0）")
        return v
    }

    /** "12.5", "1e+21", "3"; 12 significant digits, no trailing zeros. */
    fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        val s = java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros()
        return if (kotlin.math.abs(v) >= 1e15 || kotlin.math.abs(v) < 1e-6) s.toString() else s.toPlainString()
    }

    private fun normalize(s: String) = s
        .replace('×', '*').replace('÷', '/').replace('（', '(').replace('）', ')')
        .replace('，', ',').replace('−', '-').replace("**", "^").replace("π", "pi")

    private class P(val s: String) {
        var pos = 0
        fun end() = pos >= s.length
        fun peekChar() = s[pos]
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun eat(c: Char): Boolean { skipWs(); if (pos < s.length && s[pos] == c) { pos++; return true }; return false }

        fun expr(d: Int): Double {
            if (d > MAX_DEPTH) throw CalcException("嵌套过深")
            var v = term(d)
            while (true) {
                v = when {
                    eat('+') -> v + term(d)
                    eat('-') -> v - term(d)
                    else -> return v
                }
            }
        }

        fun term(d: Int): Double {
            var v = unary(d)
            while (true) {
                v = when {
                    eat('*') -> v * unary(d)
                    eat('/') -> { val r = unary(d); if (r == 0.0) throw CalcException("除以 0"); v / r }
                    eat('%') -> { val r = unary(d); if (r == 0.0) throw CalcException("对 0 取余"); v % r }
                    else -> return v
                }
            }
        }

        fun unary(d: Int): Double = when {
            eat('-') -> -unary(d + 1)
            eat('+') -> unary(d + 1)
            else -> power(d)
        }

        /** Right-associative; binds tighter than unary minus on its left (-2^2 = -4). */
        fun power(d: Int): Double {
            val base = atom(d)
            return if (eat('^')) Math.pow(base, unary(d + 1)) else base
        }

        fun atom(d: Int): Double {
            if (d > MAX_DEPTH) throw CalcException("嵌套过深")
            skipWs()
            if (end()) throw CalcException("表达式不完整")
            val c = s[pos]
            if (c == '(') {
                pos++
                val v = expr(d + 1)
                if (!eat(')')) throw CalcException("缺少右括号")
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
                val name = s.substring(start, pos).lowercase()
                when (name) {
                    "pi" -> return PI
                    "e" -> return E
                }
                val f = FUNCS[name] ?: throw CalcException("不支持的函数或名称：$name")
                if (!eat('(')) throw CalcException("$name 后面需要括号")
                val args = ArrayList<Double>()
                if (!eat(')')) {
                    do { args.add(expr(d + 1)) } while (eat(','))
                    if (!eat(')')) throw CalcException("$name 缺少右括号")
                }
                return f(args)
            }
            throw CalcException("无法识别的字符：'$c'（位置 ${pos + 1}）")
        }

        fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == '_')) pos++
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos < s.length && s[pos].isDigit()) { while (pos < s.length && s[pos].isDigit()) pos++ } else pos = save
            }
            val text = s.substring(start, pos).replace("_", "")
            return text.toDoubleOrNull() ?: throw CalcException("无效数字：$text")
        }
    }
}
