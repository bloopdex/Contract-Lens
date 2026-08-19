// Resource limits for untrusted inputs (Phase 5; Phase 0 threat model:
// "size + nesting-depth limits before parse").
//
// Every text input ContractLens reads — OpenAPI documents, GraphQL SDL,
// JSON Schema events, registries, usage graphs — is bounded by
// MAX_INPUT_BYTES before any parsing happens. Schema nesting is bounded
// separately by each adapter's depth guard (DEPTH_EXCEEDED). The limit
// is generous for real contracts (the thorn-api dump is ~73 KB) and
// large enough to be invisible in normal use; pathological documents
// fail with INPUT_TOO_LARGE instead of exhausting memory.

package dev.bloopdex.contractlens.core.error

const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
