// The application's public error model.
//
// Raw third-party exceptions (swagger-parser, snakeyaml, IO) are never
// exposed to callers or to the CLI; they are converted into one of the
// typed variants below at the adapter boundary. Every variant carries a
// stable code (for machine-readable output and tests) and a human
// message; `cause` keeps the original diagnostic detail available.

package dev.bloopdex.contractlens.core.error

sealed class ContractError(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The source file does not exist. */
    class FileNotFound(
        path: String,
    ) : ContractError("FILE_NOT_FOUND", "file not found: $path")

    /** The source file exists but cannot be read. */
    class UnreadableFile(
        path: String,
        cause: Throwable,
    ) : ContractError("UNREADABLE_FILE", "cannot read file: $path", cause)

    /** The document is not valid YAML/JSON. */
    class MalformedDocument(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("MALFORMED_DOCUMENT", "malformed contract document: $detail", cause)

    /** The document is a well-formed format ContractLens does not support (e.g. Swagger 2.0, OpenAPI 4.x). */
    class UnsupportedVersion(
        version: String,
    ) : ContractError("UNSUPPORTED_VERSION", "unsupported contract version: '$version' (supported: OpenAPI 3.0.x, 3.1.x)")

    /** The document parses but its structure is not a valid contract of the declared version. */
    class InvalidStructure(
        detail: String,
    ) : ContractError("INVALID_STRUCTURE", "invalid contract structure: $detail")

    /** The underlying parser failed in a way ContractLens cannot attribute to the document. */
    class ParserFailure(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("PARSER_FAILURE", "parser failure: $detail", cause)

    /** A $ref points at a target that does not exist. */
    class UnresolvedReference(
        ref: String,
        at: String,
    ) : ContractError("UNRESOLVED_REFERENCE", "unresolved reference '$ref' at $at")

    /** A $ref targets a multi-file or remote document (Phase 1 limitation, recorded from Phase 0's open questions). */
    class UnsupportedReference(
        ref: String,
        at: String,
    ) : ContractError("UNSUPPORTED_REFERENCE", "unsupported reference '$ref' at $at (local references only in Phase 1)")

    /** The document nests schemas beyond the phase-1 depth bound. */
    class DepthExceeded(
        at: String,
        limit: Int,
    ) : ContractError("DEPTH_EXCEEDED", "schema nesting exceeds the limit of $limit at $at")

    /** The snapshot file exists but is not a well-formed snapshot document. */
    class InvalidSnapshot(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("INVALID_SNAPSHOT", "invalid snapshot: $detail", cause)

    /** The snapshot's content hash does not match its content: tampered or corrupted. Never trusted. */
    class SnapshotIntegrity(
        path: String,
    ) : ContractError("SNAPSHOT_INTEGRITY", "snapshot integrity check failed (content modified?): $path")

    /** The snapshot store directory is missing or unusable. */
    class StoreError(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("STORE_ERROR", "snapshot store error: $detail", cause)

    /** Git commit identity could not be established (no git, not a repo, or no HEAD). */
    class GitIdentityUnavailable(
        detail: String,
    ) : ContractError("GIT_IDENTITY_UNAVAILABLE", "no git commit identity available: $detail (pass --sha explicitly)")

    /** The registry file is malformed YAML or violates the registry schema (Phase 3). */
    class RegistryInvalid(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("REGISTRY_INVALID", "invalid registry: $detail", cause)

    /** The registry declares a format version ContractLens does not support. */
    class RegistryVersionUnsupported(
        version: String,
    ) : ContractError("REGISTRY_VERSION_UNSUPPORTED", "unsupported registry version '$version' (supported: 1)")

    /** Two consumers in one registry share the same id; ids are the stable consumer identity. */
    class RegistryDuplicateId(
        id: String,
    ) : ContractError("REGISTRY_DUPLICATE_ID", "duplicate consumer id '$id' (consumer ids must be unique within one registry)")

    /** An operation selector is not "*" or a well-formed "METHOD /path-template". */
    class RegistrySelectorInvalid(
        consumerId: String,
        selector: String,
        detail: String,
    ) : ContractError("REGISTRY_SELECTOR_INVALID", "invalid operation selector '$selector' for consumer '$consumerId': $detail")

    /** Impact mapping requires the two snapshots to be the same contract (evolution, not comparison). */
    class ContractMismatch(
        oldContract: String,
        newContract: String,
    ) : ContractError(
            "CONTRACT_MISMATCH",
            "impact mapping requires both snapshots to be the same contract (old: '$oldContract', new: '$newContract')",
        )

    /** The usage graph file is malformed YAML or violates the usage schema (Phase 4). */
    class UsageInvalid(
        detail: String,
        cause: Throwable? = null,
    ) : ContractError("USAGE_INVALID", "invalid usage graph: $detail", cause)

    /** The usage graph declares a format version ContractLens does not support. */
    class UsageVersionUnsupported(
        version: String,
    ) : ContractError("USAGE_VERSION_UNSUPPORTED", "unsupported usage graph version '$version' (supported: 1)")

    /** Two usage records share the same (consumer, contract) identity. */
    class UsageDuplicateRecord(
        consumerId: String,
        contract: String,
    ) : ContractError(
            "USAGE_DUPLICATE_RECORD",
            "duplicate usage record for consumer '$consumerId' and contract '$contract' (merge the operations into one record)",
        )
}
