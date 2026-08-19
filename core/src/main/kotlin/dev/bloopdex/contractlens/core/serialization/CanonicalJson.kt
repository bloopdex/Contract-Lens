// Deterministic JSON for canonical content (snapshots, hashing).
//
// Determinism comes from three places: the model stores sorted
// collections, `encodeDefaults = true` makes serialization independent
// of default-vs-absent representation, and prettyPrint is off (no
// cosmetic differences). Identical model state always produces identical
// bytes — pinned by tests.

package dev.bloopdex.contractlens.core.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val CanonicalJson = Json {
    encodeDefaults = true
    prettyPrint = false
    ignoreUnknownKeys = false
    explicitNulls = true
}

/** Serialize `value` to its canonical byte form. */
fun <T> canonicalJsonBytes(value: T): ByteArray {
    val strategy = serializer(value::class)
    return CanonicalJson.encodeToString(strategy, value).encodeToByteArray()
}

/** Deserialize canonical bytes back into `T`. */
fun <T> parseCanonicalJson(bytes: ByteArray, type: kotlin.reflect.KClass<T>): T =
    CanonicalJson.decodeFromString(serializer(type), bytes.decodeToString())
