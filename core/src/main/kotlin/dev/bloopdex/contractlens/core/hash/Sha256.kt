// SHA-256 over canonical bytes (JDK MessageDigest — no extra dependencies).

package dev.bloopdex.contractlens.core.hash

import java.security.MessageDigest

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
