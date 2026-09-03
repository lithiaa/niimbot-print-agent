package com.niimbot.printagent.label

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

object LabelSkuGenerator {
    const val MAX_LENGTH = 12

    fun generate(productName: String, uniqueSuffix: String = UUID.randomUUID().toString().take(4)): String {
        val normalizedName = Normalizer.normalize(productName.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT)
        val suffix = uniqueSuffix.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")
            .take(4)
            .ifBlank { "0000" }
        val maxNameLength = (MAX_LENGTH - suffix.length - 1).coerceAtLeast(1)
        val namePart = normalizedName
            .replace(Regex("[^A-Z0-9]"), "")
            .take(maxNameLength)
            .ifBlank { "BARANG".take(maxNameLength) }
        return "$namePart-$suffix".take(MAX_LENGTH)
    }
}
