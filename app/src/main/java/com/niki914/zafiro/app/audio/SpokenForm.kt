package com.niki914.zafiro.app.audio

/**
 * 把「写出来给人看」的文字改成「读出来给人听」的文字。
 *
 * 实测 qwen3.5-omni-plus 念 "0.96 km — 8:11 per km" 会糊成
 * "Yongtai Na ho kilometer … eight vanabu kilometer"：数字、时间、单位一多，
 * 多语种音色就容易读成别的语言。把它们先展开成英文单词，问题就消失了。
 */
object SpokenForm {

    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun under1000(n: Int): String = when {
        n < 20 -> ones[n]
        n < 100 -> tens[n / 10] + if (n % 10 != 0) "-" + ones[n % 10] else ""
        else -> ones[n / 100] + " hundred" + if (n % 100 != 0) " and " + under1000(n % 100) else ""
    }

    private fun number(n: Long): String = when {
        n < 0 -> "minus " + number(-n)
        n < 1000 -> under1000(n.toInt())
        n < 1_000_000 -> under1000((n / 1000).toInt()) + " thousand" + if (n % 1000 != 0) " " + under1000((n % 1000).toInt()) else ""
        else -> n.toString()
    }

    /** 小数按位念：0.96 -> "zero point nine six" */
    private fun decimal(raw: String): String {
        val parts = raw.split(".")
        return number(parts[0].toLongOrNull() ?: 0) +
            " point " + parts.getOrElse(1) { "" }.filter { it.isDigit() }.map { ones[it - '0'] }.joinToString(" ")
    }

    private val units = mapOf(
        "per km" to "per kilometre", "km/h" to "kilometres per hour", "km" to "kilometres", "m" to "metres",
        "kg" to "kilograms", "g" to "grams", "mins" to "minutes", "min" to "minutes",
        "h" to "hours", "hrs" to "hours", "s" to "seconds", "%" to "percent",
        "HKD" to "Hong Kong dollars", "USD" to "US dollars", "&" to "and", "#" to "number"
    )

    fun of(text: String): String {
        var out = text
        // 时间 8:11 -> eight eleven；16:05 -> sixteen oh five
        out = Regex("\\b(\\d{1,2}):(\\d{2})\\b").replace(out) { m ->
            val h = m.groupValues[1].toInt(); val mm = m.groupValues[2]
            number(h.toLong()) + " " + if (mm.startsWith("0")) "oh " + ones[mm[1] - '0'] else number(mm.toLong())
        }
        // 小数
        out = Regex("\\b\\d+\\.\\d+\\b").replace(out) { decimal(it.value) }
        // 整数
        out = Regex("\\b\\d+\\b").replace(out) { m -> number(m.value.toLongOrNull() ?: 0) }
        // 单位（词边界）
        for ((k, v) in units) {
            out = out.replace(Regex("(?<![A-Za-z])" + Regex.escape(k) + "(?![A-Za-z])"), v)
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }
}
