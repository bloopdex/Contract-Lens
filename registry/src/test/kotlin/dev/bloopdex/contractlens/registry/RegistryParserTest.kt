// Registry adapter tests. kaml decodes the YAML document, and
// every decode-level failure becomes a typed ContractError. Unknown
// fields fail (kaml strict mode — a deliberate policy: future fields
// must come with a version bump, never be silently ignored), malformed
// YAML is never half-accepted, and parsing is deterministic.

package dev.bloopdex.contractlens.registry

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val VALID_REGISTRY =
    """
    version: 1
    consumers:
      - id: example-frontend
        kind: frontend
        contract: example-api
        operations:
          - GET /contacts/{id}
          - POST /contacts
        contact: frontend-team@example.com
        notes: main UI
      - id: OpenWA
        kind: service
        contract: example-api
        operations:
          - "*"
    """.trimIndent()

class RegistryParserTest :
    FunSpec({

        test("a valid registry parses into the typed domain") {
            val registry = RegistryParser.parse(VALID_REGISTRY, "registry.yaml")
            registry.version shouldBe 1
            registry.consumers.map { it.id } shouldBe listOf("OpenWA", "example-frontend")
            val frontend = registry.consumers.first { it.id == "example-frontend" }
            frontend.kind shouldBe ConsumerKind.FRONTEND
            frontend.contact shouldBe "frontend-team@example.com"
            frontend.selectors.map { it.raw } shouldBe listOf("GET /contacts/{id}", "POST /contacts")
            registry.consumers
                .first { it.id == "OpenWA" }
                .selectors
                .single()
                .matchesAll shouldBe true
        }

        test("an empty consumer list parses (zero registered consumers)") {
            RegistryParser.parse("version: 1\nconsumers: []\n", "registry.yaml").consumers shouldBe emptyList()
        }

        test("malformed YAML fails with a typed error carrying the source") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    RegistryParser.parse("version: 1\nconsumers: [\n", "registry.yaml")
                }
            e.code shouldBe "REGISTRY_INVALID"
            e.message shouldContain "registry.yaml"
        }

        test("an unsupported registry version fails clearly") {
            val e =
                shouldThrow<ContractError.RegistryVersionUnsupported> {
                    RegistryParser.parse("version: 2\nconsumers: []\n", "registry.yaml")
                }
            e.code shouldBe "REGISTRY_VERSION_UNSUPPORTED"
        }

        test("a missing version fails with a clear error") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    RegistryParser.parse("consumers: []\n", "registry.yaml")
                }
            e.message shouldContain "version"
        }

        test("an unknown top-level field fails (strict mode — never silently ignored)") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    RegistryParser.parse("version: 1\nconsumers: []\nsecret: x\n", "registry.yaml")
                }
            e.code shouldBe "REGISTRY_INVALID"
        }

        test("an unknown consumer field fails (strict mode)") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    RegistryParser.parse(
                        "version: 1\nconsumers:\n  - id: c\n    kind: service\n    contract: example-api\n    operations: [\"*\"]\n    owner: x\n",
                        "registry.yaml",
                    )
                }
            e.code shouldBe "REGISTRY_INVALID"
        }

        test("a wrong field type fails with a typed error") {
            shouldThrow<ContractError.RegistryInvalid> {
                RegistryParser.parse("version: 1\nconsumers: not-a-list\n", "registry.yaml")
            }
        }

        test("a quoted numeric version string parses (YAML scalar semantics)") {
            // kaml types scalars by content: a quoted "1" is still the
            // number 1. Deliberate policy: standard YAML scalar coercion
            // is accepted for numeric fields; non-numeric values fail.
            RegistryParser.parse("version: \"1\"\nconsumers: []\n", "registry.yaml").version shouldBe 1
        }

        test("a non-numeric version string fails with a typed error") {
            shouldThrow<ContractError.RegistryInvalid> {
                RegistryParser.parse("version: \"abc\"\nconsumers: []\n", "registry.yaml")
            }
        }

        test("duplicate consumer ids fail with a typed error") {
            val e =
                shouldThrow<ContractError.RegistryDuplicateId> {
                    RegistryParser.parse(
                        """
                        version: 1
                        consumers:
                          - id: dup
                            kind: service
                            contract: example-api
                            operations: ["*"]
                          - id: dup
                            kind: service
                            contract: example-api
                            operations: ["*"]
                        """.trimIndent(),
                        "registry.yaml",
                    )
                }
            e.code shouldBe "REGISTRY_DUPLICATE_ID"
        }

        test("an invalid operation selector fails with a typed error") {
            val e =
                shouldThrow<ContractError.RegistrySelectorInvalid> {
                    RegistryParser.parse(
                        "version: 1\nconsumers:\n  - id: c\n    kind: service\n    contract: example-api\n    operations: [\"GET\"]\n",
                        "registry.yaml",
                    )
                }
            e.code shouldBe "REGISTRY_SELECTOR_INVALID"
        }

        test("parsing is deterministic: the same text parses to the same domain") {
            RegistryParser.parse(VALID_REGISTRY, "registry.yaml") shouldBe RegistryParser.parse(VALID_REGISTRY, "registry.yaml")
        }

        test("key order does not matter: reordered mappings parse identically") {
            val reordered =
                """
                consumers:
                  - id: example-frontend
                    operations:
                      - GET /contacts/{id}
                      - POST /contacts
                    notes: main UI
                    contact: frontend-team@example.com
                    contract: example-api
                    kind: frontend
                version: 1
                """.trimIndent()
            val first = RegistryParser.parse(VALID_REGISTRY, "registry.yaml").consumers.first { it.id == "example-frontend" }
            val second = RegistryParser.parse(reordered, "registry.yaml").consumers.first { it.id == "example-frontend" }
            first shouldBe second
        }

        test("flow-style operation lists parse like block lists") {
            val flow = "version: 1\nconsumers:\n  - {id: c, kind: service, contract: example-api, operations: [\"*\"]}\n"
            RegistryParser
                .parse(flow, "registry.yaml")
                .consumers
                .single()
                .id shouldBe "c"
        }
    })
