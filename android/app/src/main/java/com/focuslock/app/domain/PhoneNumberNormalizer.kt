package com.focuslock.app.domain

/**
 * Locale-independent phone-number handling.
 *
 * Formatting and Unicode decimal digits are normalized for storage, but a
 * number is considered the same contact only when its complete digit sequence
 * is identical. Country codes and national trunk prefixes are never inferred.
 */
object PhoneNumberNormalizer {
    fun equivalent(a: String, b: String): Boolean {
        val x = digitsOnly(a)
        val y = digitsOnly(b)
        // Exact complete-number equality only. Suffix matching can authorize a
        // different international number that happens to share trailing digits.
        return x.length >= MIN_MATCH_DIGITS && x == y
    }

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val out = StringBuilder()
        trimmed.forEach { char ->
            val digit = char.digitToIntOrNull()
            when {
                digit != null -> out.append(digit)
                char == '+' && out.isEmpty() -> out.append('+')
            }
        }
        val digits = out.count { it.isDigit() }
        return out.toString().takeIf { digits >= MIN_MATCH_DIGITS }
    }

    private fun digitsOnly(value: String): String =
        value.mapNotNull { it.digitToIntOrNull() }.joinToString("")

    private const val MIN_MATCH_DIGITS = 7
}
