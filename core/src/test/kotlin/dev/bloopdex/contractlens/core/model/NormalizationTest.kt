package dev.bloopdex.contractlens.core.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NormalizationTest :
    StringSpec({

        "path identity replaces every template parameter with a placeholder" {
            pathIdentity("/users/{id}") shouldBe "/users/{}"
            pathIdentity("/users/{userId}") shouldBe "/users/{}"
            pathIdentity("/a/{x}/b/{y}") shouldBe "/a/{}/b/{}"
        }

        "path identity leaves parameter-less templates untouched" {
            pathIdentity("/users") shouldBe "/users"
            pathIdentity("/health/check") shouldBe "/health/check"
        }

        "status keys normalize case-insensitively" {
            normalizeStatusKey("2XX") shouldBe "2xx"
            normalizeStatusKey("200") shouldBe "200"
            normalizeStatusKey("default") shouldBe "default"
        }

        "operation ordering is by path identity then method then raw path" {
            val a = Operation("get", "/users/{id}", "/users/{}", emptyList(), null, emptyMap(), "x")
            val b = Operation("get", "/users/{userId}", "/users/{}", emptyList(), null, emptyMap(), "x")
            val c = Operation("post", "/users", "/users", emptyList(), null, emptyMap(), "x")
            listOf(b, c, a).sortedWith(operationOrder) shouldBe listOf(c, a, b)
        }

        "canonical() re-sorts properties and required sets" {
            val node =
                SchemaNode(
                    nodeType = NodeType.OBJECT,
                    types = listOf("object"),
                    format = null,
                    properties =
                        mapOf(
                            "b" to
                                SchemaNode(
                                    NodeType.SCALAR,
                                    listOf("string"),
                                    null,
                                    emptyMap(),
                                    emptyList(),
                                    null,
                                    emptyList(),
                                    false,
                                    null,
                                    null,
                                    false,
                                    "p.b",
                                ),
                            "a" to
                                SchemaNode(
                                    NodeType.SCALAR,
                                    listOf("string"),
                                    null,
                                    emptyMap(),
                                    emptyList(),
                                    null,
                                    emptyList(),
                                    false,
                                    null,
                                    null,
                                    false,
                                    "p.a",
                                ),
                        ),
                    required = listOf("b", "a", "b"),
                    items = null,
                    enumValues = emptyList(),
                    nullable = false,
                    refTarget = null,
                    constraints = null,
                    defaultPresent = false,
                    location = "root",
                )
            val canonical = node.canonical()
            canonical.properties.keys.toList() shouldBe listOf("a", "b")
            canonical.required shouldBe listOf("a", "b")
            canonical.canonical() shouldBe canonical
        }
    })
