// Normalization rules (Phase 0, ADR-001). Every rule here exists so that
// equivalent contracts produce equivalent canonical representations and
// identical canonical JSON (determinism is a pinned invariant).

package dev.bloopdex.contractlens.core.model

/**
 * Identity form of a path template: every `{paramName}` segment becomes
 * `{}`. `/users/{id}` and `/users/{userId}` are the same operation
 * (ADR-001, openapi-diff's default matcher behavior adopted as the rule).
 */
fun pathIdentity(pathTemplate: String): String {
    val sb = StringBuilder(pathTemplate.length)
    var inParam = false
    for (c in pathTemplate) {
        when {
            c == '{' -> { inParam = true; sb.append('{') }
            c == '}' && inParam -> { inParam = false; sb.append('}') }
            !inParam -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Normalized response status key: case-insensitive (OpenAPI allows
 * "2XX"/"2xx"); the normalized form is the lowercase string.
 */
fun normalizeStatusKey(status: String): String = status.lowercase()

/** Total ordering for operations: path identity first, then method. */
val operationOrder: Comparator<Operation> =
    Comparator.comparing<Operation, String> { it.pathIdentity }.thenComparing { it.method }

/** Deterministic method ordering used when sorting within one operation set. */
val methodOrder: Comparator<String> = naturalOrder()

/** Total ordering for parameters: location (`in`) first, then name. */
val parameterOrder: Comparator<Parameter> =
    Comparator.comparing<Parameter, String> { it.`in` }.thenComparing { it.name }
