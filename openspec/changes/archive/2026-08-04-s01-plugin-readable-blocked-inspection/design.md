## Context

`obsidian-plugin/bridge-client.js` already hard-requires `schemaVersion === 2` and a fixed
response shape (`parseResponse()`); `exporter-java`'s `BridgeResponse.SCHEMA_VERSION` is `3`, so
today no real exporter output is plugin-consumable. S01 is the first slice of the greenfield
`publication-exporter` project (Java 17/Maven, gate decision `dec-20260803-dd8d5f61`) and closes
that gap for exactly one command, one outcome: `inspect-publication --json` on an unsafe or
absent note returns one schema-v2 blocked response and exits non-zero.

The bridge contract itself is single-sourced at `bridge-contract/schema-v2.json` (gate decision
`dec-20260803-4834d689`), consumed by both a Java-side and a JS-side conformance test so the two
systems fail together if either drifts.

All code in this slice is governed by three standing conventions the operator set before any
implementation began (see memory `feedback-exporter-design-testing-conventions`): `/nullables` as
the default testing technique (prefer a port + embedded fake over mocking for anything
I/O-shaped), `/applying-sbpp` for class/method structure, and `/oo-design-guide` for
encapsulation/cohesion/coupling choices.

## Goals / Non-Goals

**Goals**
- `inspect-publication --json` on an unsafe path (escapes the vault root) or an absent note
  produces a schema-v2 `ok: false`, `status: "metadata_blocked"` response with a diagnostic that
  distinguishes the two cases, and the process exits non-zero.
- The note path argument reaches the process as a literal argv value — no shell is invoked, so
  spaces/metacharacters in a path are inert.
- Both `publication-exporter` and `obsidian-plugin` validate real output against the same
  `bridge-contract/schema-v2.json` file (BRG-03), not against hand-copied expectations of it.

**Non-Goals** (deferred to later slices, per the S01 scope pin in `specs/workflow-bridge/spec.md`)
- Markdown parsing or any valid-note success response (S02).
- Review workspace behaviour, `prepare`/`mark-reviewed`/`refresh-publication-queue` (S03/S05/S11).
- Any workflow status other than `metadata_blocked`.
- A real (filesystem-backed) `VaultReader` implementation — S01 ships `NullVaultReader` only.

## Decisions

### D1 — `VaultReader` port with `NullVaultReader`, no real implementation yet

```java
public interface VaultReader {
    boolean exists(VaultRelativePath notePath);
}

public final class NullVaultReader implements VaultReader {
    private final Set<VaultRelativePath> existingPaths;
    public NullVaultReader(VaultRelativePath... existing) {
        this.existingPaths = Set.copyOf(Arrays.asList(existing));
    }
    @Override public boolean exists(VaultRelativePath notePath) {
        return existingPaths.contains(notePath);
    }
}
```

**Alternatives considered:** Inline `Files.exists()` call directly in the entry point, with no
new abstraction — matches the implementation plan's "no port until forced" rule most literally,
and every S01 test would run against a real `@TempDir`.

**Why the port:** the operator's standing instruction treats `/nullables` (port + embedded
default from day one) as the default posture, not something introduced only once a second
implementation exists. `NullVaultReader`'s default configuration is "nothing exists" unless a
test supplies paths — the usual Nullables embedded-default convention. S02 adds
`FilesystemVaultReader implements VaultReader` behind the same interface with zero change to S01
code, versus a very likely mid-S02 extraction under the inline approach.

### D2 — Path-containment check is pure, separate from the existence check

The two blocked scenarios need distinct diagnostics, so they cannot share one boolean check:

```java
public final BridgeResponse execute(VaultRelativePath notePath, VaultReader vaultReader) {
    if (!notePath.isWithinVault()) {
        return BridgeResponse.blocked(Diagnostic.blocking(
            "note", "Note path escapes the vault root."));
    }
    if (!vaultReader.exists(notePath)) {
        return BridgeResponse.blocked(Diagnostic.blocking(
            "note", "Note was not found in the vault."));
    }
    ...
}
```

`VaultRelativePath` is a Whole Value (SBPP) that owns containment/normalization logic — pure
computation, no I/O, so it needs no port. This keeps `VaultReader` scoped to exactly one
responsibility (does this already-known-safe path exist), which is what makes
`NullVaultReader` trivial to write and read.

**Alternatives considered:** one combined `VaultReader.canRead(Path)` that internally does both
containment and existence checks. Rejected — it would collapse two distinct diagnostics into one
seam and make `VaultReader` responsible for path safety, which is pure logic that doesn't belong
behind an I/O port.

### D3 — Jackson + a real JSON Schema validator, not hand-rolled JSON

Add `com.fasterxml.jackson.core:jackson-databind` for `BridgeResponse` serialization, and
`com.networknt:json-schema-validator` (or equivalent) for the conformance test.

**Alternatives considered:** hand-rolled string/StringBuilder JSON construction with
hand-written field assertions in the test.

**Why:** BRG-03 requires validating *real output* against the actual `bridge-contract/schema-v2.json`
file, not against a hand-copied expectation of it. A real schema validator exercises the actual
schema; hand-written assertions can drift from the schema file silently. The response shape is
small today but grows through S02–S23, and hand-rolled JSON escaping is a recurring source of
subtle bugs for marginal savings.

### D4 — picocli from S01, even for one command

Add `info.picocli:picocli`. `InspectPublicationCommand` is a `@Command` with `@Option`-annotated
fields (`--vault`, `--note`, `--review`, `--json`) rather than a hand-rolled `argv` loop.

**Alternatives considered:** hand-rolled parsing now, introduce picocli in S03 when
`prepare`/subcommand dispatch first requires it.

**Why now:** S03/S05/S11 add three more commands with overlapping-but-not-identical flag sets
(`--jobs` present on some, absent on `inspect-publication`). Establishing the picocli command
shape in S01, while the surface is smallest, avoids re-shaping the entry point under a larger
command set later. The dependency and pattern cost is paid once, early, while it's cheap to
review.

## Risks / Trade-offs

- **[Risk]** Four small decisions (port, value type, two libraries) add more moving parts than
  the plan's literal "smallest possible surface" framing suggests for a first slice.
  **Mitigation:** each addition is a single-method interface, a single value type, or a
  widely-used library with no transitive surprises; none introduces cross-cutting state.
- **[Risk]** Path containment is easy to get subtly wrong (symlinks, `..` segments,
  drive-qualified paths, case-insensitive filesystems). **Mitigation:** containment is enforced
  in two layers, because one layer cannot cover both halves. `VaultRelativePath.isWithinVault()`
  stays a pure lexical check — no I/O — rejecting `..`/`.` segments, absolute paths, backslashes,
  empty segments, and Windows drive prefixes (`C:/…`, `c:…`) via a plain-text pattern so the
  verdict is platform-independent. Lexical checks cannot see a symlink, so the filesystem half
  lives in the `FilesystemVaultReader` adapter: it canonicalizes the vault root once with
  `Path.toRealPath()`, resolves each candidate through symlinks with `toRealPath()`, and reports
  a note whose real path falls outside the canonical root as absent — no separate diagnostic, no
  information leak about what is really there. Unresolvable paths (broken links, unrepresentable
  names) are absent too, so no `IOException`/`InvalidPathException` escapes the CLI. Covered by
  the acceptance test's unsafe-path scenario, adversarial `VaultRelativePath` cases mirroring
  what `bridge-client.js`'s `validateNotePath()` rejects client-side, and `@TempDir` symlink
  tests at both the adapter and full-CLI level — S01 must not rely on the client-side check as
  its only defense. **Known residual:** a *hard* link inside the vault to an external file is
  indistinguishable from a genuine in-vault file by path resolution alone and is not blocked.
- **[Risk]** Jackson + json-schema-validator are new runtime dependencies for a CLI that's
  meant to stay small and fast to start. **Mitigation:** both are mature, narrowly-scoped
  libraries already common in this kind of tooling; startup cost is not a stated constraint
  anywhere in the implementation plan.

## Migration Plan

Greenfield project — no existing users or data to migrate. Rollback is deleting
`publication-exporter/` and `bridge-contract/schema-v2.json`; `exporter-java` and the plugin's
default binary path are untouched by this change, so nothing is deployed or wired in yet as the
plugin's active exporter.

## Open Questions

None outstanding — D1–D4 close every fork raised during design. Package/class naming within
`publication-exporter` follows `/applying-sbpp` and `/oo-design-guide` at implementation time
and doesn't need a design-stage decision.
