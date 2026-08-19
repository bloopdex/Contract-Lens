// Property-based pinning of the two canonicalization invariants
// (Phase 1 determinism requirement):
//   1. canonical(canonical(x)) == canonical(x)          (idempotence)
//   2. bytes == bytes(parse(bytes))                     (round-trip stability)

package dev.bloopdex.contractlens.core.serialization

import dev.bloopdex.contractlens.core.model.Constraints
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.SchemaNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class SerializationPropertyTest : FunSpec({

    fun schemaNodeArb(depth: Int): Arb<SchemaNode> {
        val leaf = Arb.choice(
            Arb.string(0, 12).map {
                SchemaNode(NodeType.SCALAR, listOf(it), null, emptyMap(), emptyList(), null, emptyList(), false, null, null, false, "leaf")
            },
            Arb.int(1, 100).map {
                SchemaNode(NodeType.ENUM, listOf("string"), null, emptyMap(), emptyList(), null, listOf("v$it"), false, null, null, false, "enum")
            },
        )
        if (depth <= 0) return leaf
        val nested = Arb.choice(
            Arb.map(Arb.string(1, 4), leaf, 0, 3).map {
                SchemaNode(NodeType.OBJECT, listOf("object"), null, it, it.keys.toList(), null, emptyList(), false, null, null, false, "obj")
            },
            leaf.map {
                SchemaNode(NodeType.ARRAY, listOf("array"), null, emptyMap(), emptyList(), it, emptyList(), false, null, null, false, "arr")
            },
        )
        return Arb.choice(leaf, nested).map { it.copy(types = it.types.sorted(), required = it.required.sorted().distinct()) }
    }

    test("canonicalization is idempotent") {
        checkAll(schemaNodeArb(2)) { node ->
            node.canonical().canonical() shouldBe node.canonical()
        }
    }

    test("canonical JSON round-trips to identical bytes") {
        checkAll(schemaNodeArb(2)) { node ->
            val bytes = canonicalJsonBytes(node)
            val reparsed = parseCanonicalJson(bytes, SchemaNode::class)
            canonicalJsonBytes(reparsed) shouldBe bytes
        }
    }

    test("two serializations of the same node are byte-identical") {
        checkAll(schemaNodeArb(2)) { node ->
            canonicalJsonBytes(node) shouldBe canonicalJsonBytes(node.copy())
        }
    }

    test("constraints round-trip through canonical JSON") {
        checkAll(Arb.int(0, 50)) { n ->
            val node = SchemaNode(
                NodeType.SCALAR, listOf("string"), null, emptyMap(), emptyList(), null, emptyList(), false,
                null, Constraints(minLength = n, maxLength = n * 2), false, "c",
            )
            canonicalJsonBytes(parseCanonicalJson(canonicalJsonBytes(node), SchemaNode::class)) shouldBe canonicalJsonBytes(node)
        }
    }
})
