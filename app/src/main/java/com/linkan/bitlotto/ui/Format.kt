package com.linkan.bitlotto.ui

import java.util.Locale
import kotlin.math.abs

private val SV = Locale("sv", "SE")

fun formatHashrate(h: Double): String = when {
    h >= 1e9 -> String.format(SV, "%.2f GH/s", h / 1e9)
    h >= 1e6 -> String.format(SV, "%.2f MH/s", h / 1e6)
    h >= 1e3 -> String.format(SV, "%.1f kH/s", h / 1e3)
    else -> String.format(SV, "%.0f H/s", h)
}

fun formatDifficulty(d: Double): String = when {
    d <= 0 -> "–"
    d >= 1e12 -> String.format(SV, "%.2f T", d / 1e12)
    d >= 1e9 -> String.format(SV, "%.2f G", d / 1e9)
    d >= 1e6 -> String.format(SV, "%.2f M", d / 1e6)
    d >= 1e3 -> String.format(SV, "%.2f k", d / 1e3)
    d >= 1 -> String.format(SV, "%.2f", d)
    else -> String.format(SV, "%.4f", d)
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000_000_000 -> String.format(SV, "%.2f biljoner", n / 1e12)
    n >= 1_000_000_000 -> String.format(SV, "%.2f miljarder", n / 1e9)
    n >= 1_000_000 -> String.format(SV, "%.2f miljoner", n / 1e6)
    else -> String.format(SV, "%,d", n)
}

/** 1 BTC = 100 000 000 sats. */
fun formatBtc(sats: Long): String {
    val sign = if (sats < 0) "-" else ""
    val a = abs(sats)
    return sign + String.format(SV, "%d,%08d", a / 100_000_000, a % 100_000_000) + " BTC"
}

fun formatSats(sats: Long): String = String.format(SV, "%,d sats", sats)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(SV, "%d h %02d min", h, m) else String.format(SV, "%d min %02d s", m, s)
}

/** Formats very small or very large numbers readably, e.g. 3,2·10⁻¹⁶. */
fun formatSci(x: Double): String {
    if (x == 0.0 || x.isNaN() || x.isInfinite()) return "0"
    if (x >= 0.01 && x < 1e6) return String.format(SV, "%.3f", x)
    val exp = kotlin.math.floor(kotlin.math.log10(abs(x))).toInt()
    val mant = x / Math.pow(10.0, exp.toDouble())
    return String.format(SV, "%.1f", mant) + "·10" + superscript(exp)
}

private fun superscript(n: Int): String {
    val map = mapOf('-' to '⁻', '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')
    return n.toString().map { map[it] ?: it }.joinToString("")
}

fun formatTimestamp(sec: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", SV).format(java.util.Date(sec * 1000))
