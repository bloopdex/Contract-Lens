# Classification — how ContractLens decides breaking / non-breaking / review

This is the reference for the compatibility ruleset (ADR-001). The
implementation is `Classifier` in `:core`, pinned by the 26-case
fixture corpus and property tests; this document explains the behavior
— it does not reproduce the code.

## The classification problem

"Breaking" is not a property of a diff — it is a property of *who uses
what*. The same structural change can mean different things depending
on which side of the API it sits on (request vs response), what the
consumer does with it, and how tolerant the provider is at runtime.
A static contract document carries the structural facts and the
direction — and not much else. The ruleset's job is to answer, from
that evidence alone:

- *certain* cases with a definitive verdict (a removed operation
  breaks consumers of it);
- *insufficient-evidence* cases with `review` rather than a guess
  (whether a removed request property breaks anyone depends on the
  provider's unknown-field handling, which the contract does not
  express).

That split — decisive where the evidence is decisive, honest where it
is not — is the whole design. The research behind it (the tool survey,
the japicmp/Revapi disagreement study, the directionality evidence from
schema registries and protobuf tooling) is recorded in
[research/classification-strategy.md](research/classification-strategy.md).

## The three verdicts

| Verdict | Meaning |
|---|---|
| **breaking** | The change can reject or invalidate requests/consumers that worked before. |
| **non-breaking** | Existing consumers are unaffected by this change. |
| **review** | The available contract evidence is **insufficient to determine compatibility automatically** — a human must look. |

`review` is not a failure state. It is the honest answer when the
contract does not carry the information the rule needs — most often
because the provider's *runtime behavior* (validation tolerance,
unknown-field handling) is not expressed in the document. Conservative
classification — review instead of a false `breaking` or a false
`non-breaking` — is a deliberate policy: a wrong `breaking` verdict
creates noise and erodes trust; a wrong `non-breaking` verdict ships a
break.

## Semver is derived, never decided

A change's semver label is computed from its verdict:

| Verdict | Semver label |
|---|---|
| breaking | `major` |
| non-breaking, additive kind (new operation/parameter/body/content type/property) | `minor` |
| non-breaking, otherwise | `patch` |
| review | no label — semver cannot express human judgment |

There is no independent semver decision anywhere; `impact` and `signal`
report the highest label across the change set.

## Direction: who sends, who reads

Compatibility depends on which side of the API the change sits on.

| Direction | Meaning |
|---|---|
| **request** | the **consumer sends**, the **provider validates** — a change breaks consumers when the provider will now reject what they sent |
| **response** | the **provider sends**, the **consumer reads** — a change breaks consumers when the consumer can no longer rely on what it received |

Direction comes from the change's location grammar
(`→ request body`, `→ parameter`, `→ response`). When the location
does not determine a direction, every direction-sensitive rule returns
`review` — never a silent guess.

## The rule reference

### Operations

| Change | Verdict | Reason |
|---|---|---|
| operation added | non-breaking (minor) | new operations are invisible to existing consumers |
| operation removed | breaking (major) | consumers of this operation lose it |
| path template variable renamed (`/users/{id}` → `/users/{userId}`, identity unchanged) | non-breaking | the canonical operation identity is unchanged |
| real path change (identity differs) | breaking (major) | the operation was removed from its old path and added at a new one |

### Parameters (always request direction)

| Change | Verdict | Reason |
|---|---|---|
| parameter removed | breaking (major) | consumers still sending it lose it; validation may reject unknown parameters |
| required parameter added | breaking (major) | consumers do not send this newly required parameter |
| optional parameter added | non-breaking (minor) | existing consumers are unaffected |
| optional → required | breaking (major) | consumers not sending it now fail validation |
| required → optional | non-breaking (patch) | sending it remains valid |
| parameter location moved | review | the impact depends on how consumers send it |
| parameter schema gained/lost | review | the impact depends on provider validation |
| parameter accepted type changed | breaking (major) | type-change rule, request direction |

### Request bodies

| Change | Verdict | Reason |
|---|---|---|
| required request body added | breaking (major) | consumers must now send a body they previously omitted |
| optional request body added | non-breaking (minor) | the new body is optional for existing consumers |
| request body removed | breaking (major) | consumers still sending the body may be rejected |
| body optional → required | breaking (major) | consumers omitting the body now fail |
| body required → optional | non-breaking (patch) | omitting the body is now accepted |

### Schema properties (direction-aware)

| Change | Request direction | Response direction |
|---|---|---|
| property removed | **review** — depends on the provider's unknown-field tolerance | **breaking** — the response no longer guarantees a property consumers may read |
| property added | non-breaking (minor) — invisible to existing consumers | non-breaking (minor) — same |
| required property added | **breaking** (major) — consumers omitting it fail validation; **softened to review** when the property carries a JSON Schema default | **review** — strict clients may reject an unknown required property |
| required → optional | non-breaking (patch) — sending it remains valid | **breaking** (major) — clients must now handle its absence |
| optional → required | breaking (major) | non-breaking (patch) |

The request-side "removed property" rule is the clearest example of the
conservative policy: whether removal breaks the consumer depends on how
the provider treats unknown fields at runtime — information the
contract does not carry.

### Types, enums, constraints, nullability

| Change | Verdict | Reason |
|---|---|---|
| type changed (either direction) | breaking (major) | the accepted or emitted type changed |
| enum: any value removed | breaking (major) | consumers sending/expecting the removed value break |
| enum: values added, request direction | non-breaking (minor) | new values extend what the request accepts |
| enum: values added, response direction | review | consumers may not recognize new values (non-breaking only under an `x-extensible-enum` annotation, which the canonical model does not carry) |
| constraints tightened | review | the contract narrowed its constraints — the effect depends on real data |
| constraints relaxed | non-breaking (patch) | the contract relaxed its constraints |
| constraints both tightened and relaxed | review | mixed direction |
| nullability: request non-null → nullable | non-breaking (patch) | the request accepts null where it previously rejected it |
| nullability: request nullable → non-null | review | the request rejects null where it previously accepted it |
| nullability: response non-null → nullable | review | the response may now emit null where it previously never did |
| nullability: response nullable → non-null | non-breaking (patch) | the response no longer emits null |

### Status codes and content types

| Change | Verdict | Reason |
|---|---|---|
| response status removed | breaking (major) | clients handling this status can no longer receive it |
| response status added | review | a new status may appear; clients may not handle it |
| content type removed | breaking (major) | consumers requesting this representation lose it |
| content type added | non-breaking (minor) | an additional representation is available |

### Kinds without enough evidence

| Change | Verdict | Reason |
|---|---|---|
| `REF_TARGET_CHANGED` | review | the referenced schema changed; the substitution's compatibility is unknown |
| `DEFAULT_CHANGED` | review | defaults changed; the effect is context-dependent |
| `ITEMS_CHANGED` | review | the array item schema changed; the effect is context-dependent |

## Rename candidates

A removed property and an added property of the **same type in the same
schema** are paired as a rename candidate: **both** changes get
`review`, never `breaking`. Renames are the classic
break-vs-new-plus-deprecated ambiguity, and the evidence in a static
contract cannot distinguish them — so the tool surfaces the pair for
human judgment with a deterministic pairing rule (first unused removal
with first unused addition of the same type, sorted by location).

## Unknown and undetermined changes

- **Undetermined direction:** any direction-sensitive rule whose change
  location carries no direction grammar falls back to `review` with the
  reason "the change's direction could not be determined". This cannot
  be triggered by the diff engine's own output shapes, but the
  classifier defends against it rather than guessing.
- **Evidence-poor kinds:** `REF_TARGET_CHANGED`, `DEFAULT_CHANGED`, and
  `ITEMS_CHANGED` are always `review` (see the table above) — the
  substitution, the default, or the item schema changed, and the
  contract does not carry what the impact depends on.
- **Anything the classifier does not recognize is `review`**, never a
  silent default in either direction.

## Examples

**Enum narrowed in the response (breaking).**

```yaml
# before                         # after
enum: [admin, editor, viewer]    enum: [admin, viewer]
```

`ENUM_CHANGED`, response direction → **breaking (major)** — a consumer
that observed `editor` can no longer rely on it. (The same change in a
*request* body is also breaking: old consumers may still send it.)

**Optional request property added (non-breaking, additive).**

```yaml
# before                          # after
properties:                       properties:
  name: {type: string}              name: {type: string}
                                    role: {type: string}   # new, not required
```

`PROPERTY_ADDED`, request direction → **non-breaking (minor)** —
existing consumers never send it, and the provider does not require it.

**Request property removed (review).**

```yaml
# before                          # after
properties:                       properties:
  name: {type: string}              name: {type: string}   # legacyField removed
  legacyField: {type: string}
```

`PROPERTY_REMOVED`, request direction → **review** — whether consumers
still sending `legacyField` break depends on the provider's
unknown-field handling, which the contract does not express. The
response-direction counterpart is unambiguously breaking.

**Response required property added (review).**

```yaml
# before                          # after
required: [id]                    required: [id, status]
```

`REQUIRED_PROPERTY_ADDED`, response direction → **review** — strict
clients may reject a response carrying an unknown required property;
lenient ones will not. (In the request direction this is breaking:
consumers do not send the new field.)
