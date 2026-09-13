// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.crypto

private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun ByteArray.toBase64Url(): String = encodeBase64(this)
    .trimEnd('=')
    .replace('+', '-')
    .replace('/', '_')

fun String.fromBase64Url(): ByteArray {
    val normalized = replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return decodeBase64(padded)
}

private fun encodeBase64(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        val n = (b0 shl 16) or (b1 shl 8) or b2
        out.append(TABLE[(n ushr 18) and 63])
        out.append(TABLE[(n ushr 12) and 63])
        out.append(if (i + 1 < bytes.size) TABLE[(n ushr 6) and 63] else '=')
        out.append(if (i + 2 < bytes.size) TABLE[n and 63] else '=')
        i += 3
    }
    return out.toString()
}

private fun decodeBase64(value: String): ByteArray {
    if (value.isEmpty()) return ByteArray(0)
    val clean = value.filterNot(Char::isWhitespace)
    require(clean.length % 4 == 0) { "Invalid Base64 length" }
    val out = ArrayList<Byte>((clean.length / 4) * 3)
    var i = 0
    while (i < clean.length) {
        fun v(c: Char): Int = when (c) {
            '=' -> 0
            else -> TABLE.indexOf(c).also { require(it >= 0) { "Invalid Base64 character" } }
        }
        val c0 = clean[i]
        val c1 = clean[i + 1]
        val c2 = clean[i + 2]
        val c3 = clean[i + 3]
        val n = (v(c0) shl 18) or (v(c1) shl 12) or (v(c2) shl 6) or v(c3)
        out += ((n ushr 16) and 0xff).toByte()
        if (c2 != '=') out += ((n ushr 8) and 0xff).toByte()
        if (c3 != '=') out += (n and 0xff).toByte()
        i += 4
    }
    return out.toByteArray()
}

fun ByteArray.hexLower(): String = joinToString("") { b ->
    (b.toInt() and 0xff).toString(16).padStart(2, '0')
}


fun ByteArray.hexUpper(): String = joinToString("") { b ->
    (b.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
}

fun String.fromHexFlexible(): ByteArray {
    val clean = filter(Char::isLetterOrDigit).uppercase()
    require(clean.length % 2 == 0) { "Invalid hex length" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun String.groupedPairingCode(groupSize: Int = 4): String =
    filter(Char::isLetterOrDigit).uppercase().chunked(groupSize).joinToString("-")
