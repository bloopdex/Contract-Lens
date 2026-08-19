// Usage-graph adapter tests (Phase 4 groundwork): kaml strict decode,
// typed validation (version, duplicates, selectors, field paths),
// deterministic merging of duplicate operations, and the failure paths.

package dev.bloopdex.contractlens.registry

import dev.bloopdex.contractlens.core.error.ContractError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val VALID_USAGE =
    """
    version: 1
    consumers:
      - id: thornwa-frontend
        contract: thorn-api
        operations:
          - operation: GET /contacts/{id}
            responseFields:
              - email
              - profile.address.city
      - id: OpenWA
        contract: thorn-api
        operations:
          - operation: "*"
            requestFields:
              - sessionId
    """.trimIndent()

class UsageParserTest :
    FunSpec({

        test("a valid usage graph parses into the typed domain") {
            val graph = UsageParser.parse(VALID_USAGE, "usage.yaml")
            graph.version shouldBe 1
            graph.records.map { it.consumer } shouldBe listOf("OpenWA", "thornwa-frontend")
            val frontend = graph.records.first { it.consumer == "thornwa-frontend" }
            frontend.contract shouldBe "thorn-api"
            frontend.operations
                .single()
                .selector
                .matches("get", "/contacts/{}") shouldBe true
            frontend.operations.single().responseFields shouldBe listOf("email", "profile.address.city")
            graph.records
                .first { it.consumer == "OpenWA" }
                .operations
                .single()
                .selector.matchesAll shouldBe true
        }

        test("duplicate operations for one consumer merge deterministically (field lists union)") {
            val text =
                """
                version: 1
                consumers:
                  - id: c
                    contract: thorn-api
                    operations:
                      - operation: GET /users/{id}
                        responseFields: [email]
                      - operation: GET /users/{userId}
                        responseFields: [email, profile]
                """.trimIndent()
            val graph = UsageParser.parse(text, "usage.yaml")
            val operations = graph.records.single().operations
            operations.size shouldBe 1 // same canonical identity
            operations.single().responseFields shouldBe listOf("email", "profile")
            operations.single().selector.raw shouldBe "GET /users/{id}"
        }

        test("field paths are sorted and deduplicated") {
            val text =
                """
                version: 1
                consumers:
                  - id: c
                    contract: thorn-api
                    operations:
                      - operation: "*"
                        responseFields: [z, a, z]
                """.trimIndent()
            UsageParser
                .parse(text, "usage.yaml")
                .records
                .single()
                .operations
                .single()
                .responseFields shouldBe listOf("a", "z")
        }

        test("an invalid operation selector fails with the registry's typed error") {
            val e =
                shouldThrow<ContractError.RegistrySelectorInvalid> {
                    UsageParser.parse(
                        "version: 1\nconsumers:\n  - id: c\n    contract: thorn-api\n    operations:\n      - operation: GET\n",
                        "usage.yaml",
                    )
                }
            e.code shouldBe "REGISTRY_SELECTOR_INVALID"
        }

        test("an invalid field path fails with a typed error") {
            shouldThrow<ContractError.UsageInvalid> {
                UsageParser.parse(
                    "version: 1\nconsumers:\n  - id: c\n    contract: thorn-api\n    operations:\n      - operation: \"*\"\n        responseFields: [\".email\"]\n",
                    "usage.yaml",
                )
            }
        }

        test("duplicate (consumer, contract) records fail with a typed error") {
            val e =
                shouldThrow<ContractError.UsageDuplicateRecord> {
                    UsageParser.parse(
                        "version: 1\nconsumers:\n  - id: c\n    contract: thorn-api\n    operations: []\n  - id: c\n    contract: thorn-api\n    operations: []\n",
                        "usage.yaml",
                    )
                }
            e.code shouldBe "USAGE_DUPLICATE_RECORD"
        }

        test("an unsupported version fails clearly") {
            val e =
                shouldThrow<ContractError.UsageVersionUnsupported> {
                    UsageParser.parse("version: 2\nconsumers: []\n", "usage.yaml")
                }
            e.code shouldBe "USAGE_VERSION_UNSUPPORTED"
        }

        test("malformed YAML and unknown fields fail with typed errors") {
            shouldThrow<ContractError.UsageInvalid> {
                UsageParser.parse("version: 1\nconsumers: [\n", "usage.yaml")
            }
            val unknown =
                shouldThrow<ContractError.UsageInvalid> {
                    UsageParser.parse("version: 1\nconsumers: []\nextra: true\n", "usage.yaml")
                }
            unknown.message shouldContain "usage.yaml"
        }

        test("parsing is deterministic") {
            UsageParser.parse(VALID_USAGE, "usage.yaml") shouldBe UsageParser.parse(VALID_USAGE, "usage.yaml")
        }
    })
