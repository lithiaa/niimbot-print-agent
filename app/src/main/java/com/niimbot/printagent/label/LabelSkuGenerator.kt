package com.niimbot.printagent.label

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

object LabelSkuGenerator {
    fun generate(productName: String, uniqueSuffix: String = UUID.randomUUID().toString().take(4)): String {
        val normalizedName = Normalizer.normalize(productName.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT)
        val namePart = normalizedName
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("-")
            .take(24)
            .trim('-')
            .ifBlank { "BARANG" }
        val suffix = uniqueSuffix.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")
            .take(4)
            .ifBlank { "0000" }
        return "$namePart-$suffix"
    }
}
