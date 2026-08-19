// Deterministic JSON for canonical content (snapshots, hashing).
//
// Determinism comes from three places: the model stores sorted
// collections, `encodeDefaults = true` makes serialization independent
// of default-vs-absent representation, and prettyPrint is off (no
// cosmetic differences). Identical model state always produces identical
// bytes — pinned by tests.

package dev.bloopdex.contractlens.core.serialization

import kotlinx.serialization.json.Json

val CanonicalJson =
    Json {
        encodeDefaults = true
        prettyPrint = false
        ignoreUnknownKeys = false
        explicitNulls = true
    }

/** Serialize `value` to its canonical byte form. */
inline fun <reified T> canonicalJsonBytes(value: T): ByteArray = CanonicalJson.encodeToString(value).encodeToByteArray()

/** Deserialize canonical bytes back into `T`. */
inline fun <reified T> parseCanonicalJson(bytes: ByteArray): T = CanonicalJson.decodeFromString(bytes.decodeToString())
