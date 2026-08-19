// Snapshot persistence: round-trip, integrity verification, corruption
// detection, format versioning, and index behavior on restart.

package dev.bloopdex.contractlens.snapshot

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.SchemaNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.readText

class SnapshotStoreTest :
    FunSpec({

        fun surface(name: String): ContractSurface =
            ContractSurface(
                name = name,
                kind = "openapi",
                formatVersion = "3.0.3",
                operations =
                    listOf(
                        Operation(
                            method = "get",
                            path = "/ping",
                            pathIdentity = "/ping",
                            parameters = emptyList(),
                            requestBody = null,
                            responses =
                                mapOf(
                                    "200" to
                                        dev.bloopdex.contractlens.core.model.Response(
                                            content =
                                                mapOf(
                                                    "application/json" to
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
                                                            "paths./ping.get.responses.200",
                                                        ),
                                                ),
                                            location = "paths./ping.get.responses.200",
                                        ),
                                ),
                            location = "paths./ping.get",
                        ),
                    ),
            )

        val identity = SnapshotIdentity(kind = "git-commit", sha = "0123456789abcdef0123456789abcdef01234567")

        fun store(): SnapshotStore = SnapshotStore(Files.createTempDirectory("contractlens-store"))

        test("save then load round-trips the document") {
            val s = store()
            val document = buildSnapshot("ping", "/src/ping.yaml", identity, "2026-08-19T00:00:00Z", surface("ping"))
            val path = s.save(document)
            val loaded = s.load("ping", identity.sha)
            loaded shouldBe document
            loaded.surface shouldBe document.surface
            path.fileName.toString() shouldBe "ping@0123456789abcdef0123456789abcdef01234567.snapshot.json"
        }

        test("capturedAt is variable metadata and does not affect the content hash") {
            val a = buildSnapshot("ping", null, identity, "2026-08-19T00:00:00Z", surface("ping"))
            val b = buildSnapshot("ping", null, identity, "2026-08-20T12:34:56Z", surface("ping"))
            a.contentHash shouldBe b.contentHash
        }

        test("a tampered surface fails integrity verification loudly") {
            val s = store()
            val document = buildSnapshot("ping", null, identity, "2026-08-19T00:00:00Z", surface("ping"))
            val path = s.save(document)

            // Forge: keep the original hash but swap the surface content.
            val bytes = Files.readAllBytes(path)
            val text = String(bytes)
            val forged = text.replace("\"ping\"", "\"pong\"")
            Files.write(path, forged.toByteArray())

            val e = shouldThrow<ContractError.SnapshotIntegrity> { s.load("ping", identity.sha) }
            e.code shouldBe "SNAPSHOT_INTEGRITY"
        }

        test("corrupt JSON is reported as an invalid snapshot, not trusted") {
            val s = store()
            val document = buildSnapshot("ping", null, identity, "2026-08-19T00:00:00Z", surface("ping"))
            val path = s.save(document)
            Files.write(path, "{\"formatVersion\": 1, broken".toByteArray())
            val e = shouldThrow<ContractError.InvalidSnapshot> { s.load("ping", identity.sha) }
            e.code shouldBe "INVALID_SNAPSHOT"
        }

        test("an unsupported snapshot format version is refused") {
            val s = store()
            val document = buildSnapshot("ping", null, identity, "2026-08-19T00:00:00Z", surface("ping"))
            val path = s.save(document)
            val text = path.readText().replace("\"formatVersion\":1", "\"formatVersion\":99")
            Files.write(path, text.toByteArray())
            val e = shouldThrow<ContractError.InvalidSnapshot> { s.load("ping", identity.sha) }
            e.message shouldContain "format version 99"
        }

        test("the index rebuilds by scanning and marks corrupt entries") {
            val s = store()
            s.save(buildSnapshot("ping", null, identity, "2026-08-19T00:00:00Z", surface("ping")))
            s.save(buildSnapshot("ping", null, SnapshotIdentity("git-commit", "a".repeat(40)), "2026-08-19T00:00:00Z", surface("ping")))
            Files.writeString(
                s
                    .list()
                    .first()
                    .path.parent
                    .resolve("garbage@bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.snapshot.json"),
                "not json at all",
            )

            val index =
                SnapshotStore(
                    s
                        .list()
                        .first()
                        .path.parent,
                ).list() // fresh store instance = index rebuild
            index.size shouldBe 3
            index.count { it.error == null } shouldBe 2
            val corrupt = index.single { it.error != null }
            corrupt.contract shouldBe "garbage"
            corrupt.error shouldContain "INVALID_SNAPSHOT"
        }

        test("loading a missing snapshot reports the store error") {
            val s = store()
            val e = shouldThrow<ContractError.StoreError> { s.load("absent", "b".repeat(40)) }
            e.code shouldBe "STORE_ERROR"
        }

        test("a store path that is a file fails with a store error") {
            val file = Files.createTempFile("contractlens-notadir", "txt")
            val e =
                shouldThrow<ContractError.StoreError> { SnapshotStore(file).save(buildSnapshot("x", null, identity, "t", surface("x"))) }
            e.code shouldBe "STORE_ERROR"
        }
    })
