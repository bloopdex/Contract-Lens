// Registry validation unit tests (Phase 3). validateRegistry() turns the
// raw document shape into the typed domain; every failure must be typed,
// deterministic, and explainable. The kaml-level decode failures (bad
// YAML, wrong types, unknown fields) are pinned in :registry.

package dev.bloopdex.contractlens.core.registry

import dev.bloopdex.contractlens.core.error.ContractError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RegistryValidationTest :
    FunSpec({

        fun rawConsumer(
            id: String,
            contract: String = "thorn-api",
            operations: List<String>? = listOf("*"),
            kind: String = "frontend",
        ): RawConsumer = RawConsumer(id = id, kind = kind, contract = contract, operations = operations)

        fun registryOf(vararg consumers: RawConsumer): RawRegistry = RawRegistry(version = 1, consumers = consumers.toList())

        test("a valid registry validates into the typed domain") {
            val registry = validateRegistry(registryOf(rawConsumer("consumer-a")), "test")
            registry.version shouldBe 1
            val consumer = registry.consumers.single()
            consumer.id shouldBe "consumer-a"
            consumer.kind shouldBe ConsumerKind.FRONTEND
            consumer.contract shouldBe "thorn-api"
            consumer.selectors.single().matchesAll shouldBe true
        }

        test("an empty consumer list is valid — zero registered consumers") {
            validateRegistry(RawRegistry(version = 1, consumers = emptyList()), "test").consumers shouldBe emptyList()
        }

        test("consumers are sorted by id regardless of document order") {
            val registry = validateRegistry(registryOf(rawConsumer("z-consumer"), rawConsumer("a-consumer")), "test")
            registry.consumers.map { it.id } shouldBe listOf("a-consumer", "z-consumer")
        }

        test("duplicate consumer ids fail with a typed error") {
            val e =
                shouldThrow<ContractError.RegistryDuplicateId> {
                    validateRegistry(registryOf(rawConsumer("dup"), rawConsumer("dup")), "test")
                }
            e.code shouldBe "REGISTRY_DUPLICATE_ID"
        }

        test("an unsupported registry version fails loudly") {
            val e =
                shouldThrow<ContractError.RegistryVersionUnsupported> {
                    validateRegistry(RawRegistry(version = 2, consumers = emptyList()), "test")
                }
            e.code shouldBe "REGISTRY_VERSION_UNSUPPORTED"
        }

        test("a missing version field is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(RawRegistry(version = null, consumers = emptyList()), "test")
                }
            e.message shouldContain "version"
        }

        test("a missing consumers field is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(RawRegistry(version = 1, consumers = null), "test")
                }
            e.message shouldContain "consumers"
        }

        test("a blank consumer id is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("   ")), "test")
                }
            e.message shouldContain "id"
        }

        test("a missing kind is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", kind = "")), "test")
                }
            e.message shouldContain "kind"
        }

        test("an unknown consumer kind is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", kind = "database")), "test")
                }
            e.message shouldContain "database"
        }

        test("a missing contract is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", contract = "")), "test")
                }
            e.message shouldContain "contract"
        }

        test("a missing operations list is invalid") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", operations = null)), "test")
                }
            e.message shouldContain "operations"
        }

        test("an empty operations list is invalid (use [\"*\"] to select everything)") {
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", operations = emptyList())), "test")
                }
            e.message shouldContain "operations"
        }

        test("a selector without a method is invalid") {
            val e =
                shouldThrow<ContractError.RegistrySelectorInvalid> {
                    validateRegistry(registryOf(rawConsumer("c", operations = listOf("/users"))), "test")
                }
            e.code shouldBe "REGISTRY_SELECTOR_INVALID"
        }

        test("a selector without a path is invalid") {
            shouldThrow<ContractError.RegistrySelectorInvalid> {
                validateRegistry(registryOf(rawConsumer("c", operations = listOf("GET"))), "test")
            }
        }

        test("a selector whose path has no leading slash is invalid") {
            shouldThrow<ContractError.RegistrySelectorInvalid> {
                validateRegistry(registryOf(rawConsumer("c", operations = listOf("GET users"))), "test")
            }
        }

        test("a selector with a space inside the path is invalid") {
            shouldThrow<ContractError.RegistrySelectorInvalid> {
                validateRegistry(registryOf(rawConsumer("c", operations = listOf("GET /a /b"))), "test")
            }
        }

        test("a blank selector is invalid") {
            shouldThrow<ContractError.RegistrySelectorInvalid> {
                validateRegistry(registryOf(rawConsumer("c", operations = listOf("   "))), "test")
            }
        }

        test("selectors are normalized: lowercase method, template identity") {
            val selector = parseSelector("c", "GET /users/{id}")
            selector.matchesAll shouldBe false
            selector.method shouldBe "get"
            selector.path shouldBe "/users/{id}"
            selector.pathIdentity shouldBe "/users/{}"
            // matches() compares canonical identities: the selector's
            // template identity is compared against the OPERATION's
            // identity (callers pass the normalized form, like the engine
            // does), so /users/{id} and /users/{userId} are one operation.
            selector.matches("get", "/users/{}") shouldBe true
            selector.matches("get", "/other/{}") shouldBe false
            selector.matches("post", "/users/{}") shouldBe false
        }

        test("the wildcard selector matches everything") {
            val selector = parseSelector("c", "*")
            selector.matchesAll shouldBe true
            selector.matches("get", "/users/{}") shouldBe true
        }

        test("equivalent selectors dedupe to the first occurrence") {
            val registry =
                validateRegistry(
                    registryOf(rawConsumer("c", operations = listOf("*", "*", "GET /users/{id}", "GET /users/{userId}"))),
                    "test",
                )
            val selectors = registry.consumers.single().selectors
            selectors.size shouldBe 2
            selectors[0].matchesAll shouldBe true
            selectors[1].raw shouldBe "GET /users/{id}"
        }

        test("selectors are sorted deterministically (wildcard first)") {
            val registry =
                validateRegistry(
                    registryOf(rawConsumer("c", operations = listOf("POST /x", "*", "GET /a"))),
                    "test",
                )
            registry.consumers
                .single()
                .selectors
                .map { it.raw } shouldBe listOf("*", "GET /a", "POST /x")
        }

        test("validation is deterministic: identical raw documents produce identical domains") {
            val raw = registryOf(rawConsumer("b"), rawConsumer("a"))
            validateRegistry(raw, "test") shouldBe validateRegistry(raw, "test")
        }
    })
