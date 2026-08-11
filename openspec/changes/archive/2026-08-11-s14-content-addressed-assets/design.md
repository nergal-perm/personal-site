## Context

S13 (`dec-20260810-cc3f02ed`) wired `LinkResolver` into `PrepareHandler`'s `prepare`/`sourceFreshness` paths. `LinkResolver.appendLink` already classifies an embed target as asset-like by extension (`isAssetTarget`, `ASSET_EXTENSIONS`) but explicitly leaves it untouched — `output.append(link.group())` copies the raw `![[image.png]]` token verbatim. PCM-05 requires the exporter to resolve that target, prefer an exact vault-relative match over a unique basename fallback, reject ambiguous/unsafe targets with a blocking diagnostic before any candidate replacement, and materialize each accepted asset under a deterministic content-derived name with byte-identical dedup.

`VaultReader` (`vault/VaultReader.java`) reads note text only (`readSource`, `exists`, `listPublishCandidates`) — no binary asset access exists. `CandidateWorkspace.install(...)` (`candidate/CandidateWorkspace.java`) takes 8 parameters (identity + 6 strings + `ReferenceMap`) and has no asset concept. `CandidateSnapshot` is the read-side value type for `CandidateWorkspace.read(...)` — but it is *also* `ApprovedSnapshotWorkspace.read(...)`'s return type (confirmed: `approved/ApprovedSnapshotWorkspace.java` imports and returns `CandidateSnapshot`), even though `ApprovedSnapshotWorkspace.install(...)` keeps its own independent flat-parameter signature, unrelated to `CandidateSnapshot`'s shape. This coupling matters for where an `assets` field can safely live (see Decision 3).

`exporter-java`'s `AssetResolver`/`ResolvedAsset` (`astroexport/assets/`) were read as behavioural evidence only (compatibility oracle, never a code donor): SHA-256 content-addressing with suffix canonicalization (`.jpeg` → `.jpg`), exact-path-before-basename-fallback (basename fallback must be unique among visible files), traversal/symlink/hidden-path escape rejection, and byte-identical dedup keyed by digest with a suffix-family consistency check. `public/assets/vault/` is already a reserved, site-gated managed path — confirmed live in `site/scripts/check-content.mjs` and `site/tests/release-provenance.test.mjs`, not just legacy color — so this slice's public route scheme reuses it rather than inventing a new one.

## Goals / Non-Goals

**Goals:**
- Resolve an asset-like embed target (`![[image.png]]`) left untouched by `LinkResolver` into a content-addressed public reference (`/assets/vault/{sha256}{normalized-ext}`), rewritten as a uniform Markdown image/link (`![label](path)`) for every accepted asset type (image, audio, video alike — see functional spec's exclusion of type-specific HTML rendering).
- Prefer an exact safe vault-relative match; fall back to a unique basename match; block with an asset diagnostic — before any candidate is installed or replaced — on no-match, ambiguous-basename, or an unsafe (traversal/symlink-escaping) target.
- Materialize each accepted asset's bytes into the candidate workspace, atomically with the RU/EN bodies it's referenced from, deduplicating identical bytes referenced more than once within the same `prepare` call.
- Prove the behaviour in-memory first (fake asset bytes, fake candidate workspace), then run the same contract against a real filesystem adapter, per this project's outside-in slicing discipline.

**Non-Goals:**
- Type-specific rendering (HTML5 `<audio>`/`<video>` tags, numeric-alias-as-width sizing) — settled in the functional collaborative-design pass in favor of a uniform Markdown link.
- Threading materialized assets from candidate into `ApprovedSnapshotWorkspace` or `ReleaseOutputStore`/`BuildFromReviewHandler` — both confirmed untouched today (no `asset` references in either file) and stay that way this slice.
- Asset variants, image optimization, remote (non-vault) assets, or media types beyond the existing `ASSET_EXTENSIONS` set.
- Vault-wide asset dedup or caching across separate `prepare` calls — dedup is scoped to the accepted-asset set of one `prepare` invocation, matching PCM-05's scenario phrasing ("multiple accepted references" within one resolution, not a persistent store).

## Decisions

### 1. A second pipeline stage (`AssetResolver`), not a `LinkResolver` change

Add a new `prepare`-package class, `AssetResolver`, composed *after* `LinkResolver.resolve(...)` succeeds — mirroring the existing `MarkdownNormalizer.normalize(body).resolve(normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(...))` chain:

```java
MarkdownNormalizer.normalize(intake.body()).resolve(
    normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
        resolvedBody -> AssetResolver.resolve(resolvedBody, vaultAssetReader).resolve(
                assetOutcome -> prepareNormalizedEssay(..., assetOutcome.body(), assetOutcome.assets(), ...),
                PrepareHandler::assetBlockedFailure),
        PrepareHandler::transclusionBlockedFailure),
    position -> unclosedCommentFailure(position));
```

`AssetResolver` re-scans for the same `WIKILINK`-shaped tokens `LinkResolver` recognized as asset-like but left untouched. This is safe: `LinkResolver`'s output contains no *new* `[[...]]`-shaped text for any other case (its outputs are either a Markdown link, a plain label, or an untouched asset-embed token) — so a second protected-region-aware scan restricted to embed+asset-extension targets finds exactly, and only, the remaining raw tokens.

**Alternative considered:** fold asset resolution directly into `LinkResolver.appendLink`. Rejected — `LinkResolver` is tested and stable after S13; every requirement realized so far in this pipeline (`MarkdownNormalizer` for PCM-04, `LinkResolver` for PCM-03) is its own single-responsibility, single-concern stage. Widening `LinkResolver` to also own asset-byte resolution, hashing, and dedup would violate that established one-concern-per-stage precedent and couple two independently-evolving requirements (PCM-03, PCM-05) into one class.

### 2. A new dedicated port, `VaultAssetReader`, not an extension of `VaultReader`

```java
public interface VaultAssetReader {
    AssetLookup resolve(String reference);

    static VaultAssetReader create(Path vaultRoot) { return new FilesystemVaultAssetReader(vaultRoot); }
    static VaultAssetReader createNull(Map<String, byte[]> assetsByVaultRelativePath) { return new NullVaultAssetReader(assetsByVaultRelativePath); }
}
```

`AssetLookup` is a small closed result type (`found(byte[] content)`, `ambiguous()`, `unsafe()`, `notFound()`) — `AssetResolver` (domain) turns `notFound`/`ambiguous`/`unsafe` uniformly into a blocking asset diagnostic (PCM-05 doesn't distinguish them in its scenario text: "no exact target and multiple basename matches, or a target that escapes... blocked with an asset diagnostic" — one outcome, several causes). Exact-path-vs-basename-fallback resolution, traversal/symlink/hidden-path rejection, and basename-uniqueness enforcement live in `FilesystemVaultAssetReader`, behaviorally mirroring `exporter-java`'s `AssetResolver.resolveSource` (evidence, not code).

Content hashing (SHA-256 + suffix normalization) and the public-route string (`/assets/vault/{hash}{ext}`) are pure functions of the resolved bytes, computed in the domain `AssetResolver`, not the port — consistent with the plan's "pure in-process behaviour uses no port merely for architectural symmetry." `ContentHash` gains a `sha256Hex(byte[])` overload (it currently only hashes `String`).

**Alternative considered:** extend `VaultReader` with asset-resolution methods. Rejected — `VaultReader`'s current responsibility is note-text admission with `VaultRelativePath` confinement; binary-asset resolution (exact/basename/traversal/dedup) is a materially different concern with its own safety rules. A dedicated port keeps both interfaces small and matches the plan's explicit budget of "at most one new production boundary adapter" for this slice as a standalone item, rather than silently growing an existing one.

### 3. Assets thread through `CandidateWorkspace.install(...)` as a separate parameter, not folded into `CandidateSnapshot`

Initial collaborative-design direction was to reuse `CandidateSnapshot` as `install(identity, CandidateSnapshot)`'s single parameter, since it already bundles the same 6 strings + `ReferenceMap`. Revisited after confirming `CandidateSnapshot` is *also* `ApprovedSnapshotWorkspace.read()`'s return type: growing it with an `assets` field would force every `ApprovedSnapshotWorkspace` adapter and test (`FilesystemApprovedSnapshotWorkspaceTest`, `NullApprovedSnapshotWorkspaceTest`, `MarkReviewedHandlerTest`, and others) to thread an always-empty `List.of()` through a constructor they have no reason to know about — rippling into code this slice's Haft problem explicitly declared untouched (`approved-snapshot ... code paths`).

Instead:

```java
public interface CandidateWorkspace {
    void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets);
    Optional<CandidatePaths> find(PublicationIdentity identity);
    Optional<CandidateSnapshot> read(PublicationIdentity identity);
}

public record CandidateAsset(String publicName, byte[] content) {}
```

`CandidateSnapshot` itself stays exactly as it is today — untouched, still safely shared with `ApprovedSnapshotWorkspace`. `FilesystemCandidateWorkspace.install(...)` writes each `CandidateAsset` into the staged directory (e.g. `assets/{publicName}`) alongside `ru.md`/`en.md`/etc., inside the same `StagedDirectoryInstall` staging-then-atomic-move sequence already proven for the existing fields — so a candidate and its referenced assets replace atomically, never partially. `read()`/`find()` do not need to expose assets back out in this slice: nothing downstream (inspection, mark-reviewed) needs to read asset bytes back from `CandidateWorkspace` within this slice's scope, since propagation into `ApprovedSnapshotWorkspace` is explicitly out of scope.

The `mirrorApprovedCandidate` call site (`PrepareHandler.ensureCandidateMirrorsApproved`, used when the RU source is unchanged from the approved baseline) passes an empty asset list — consistent with `ApprovedSnapshotWorkspace` carrying no asset data this slice.

### 4. Resolution runs once on RU, ahead of translation; EN must retain the same asset routes

Same discipline S13 established for link routes (PCM-03: "resolution runs once on the Russian source ahead of translation, and the same route text is reused, untranslated, in the derived English candidate"). `AssetResolver` runs on the RU body only, before `TranslationJob.forSource(...)`; the translation worker receives already-asset-resolved RU text and must preserve the `/assets/vault/...` reference verbatim.

`EnglishCandidateValidator` gains a check mirroring its existing `droppedExternalUrls` logic (`prepare/EnglishCandidateValidator.java`): an `/assets/vault/` reference present in the RU body but absent from the EN body is a translation-validation failure, the same way a dropped external URL is today. This closes an otherwise-silent gap — nothing currently verifies the translation worker preserves a resolved asset reference, and PCM-06 already requires English content to "retain external URLs" and add/drop no fields.

### 5. Outside-in sequencing

1. In-memory acceptance test: `VaultAssetReader.createNull(Map.of(...))` + `CandidateWorkspace.createNull()` prove exact-match, ambiguous/unsafe-block-before-replacement, and within-call dedup (PCM-05's three scenarios) in-process, sub-second.
2. `FilesystemVaultAssetReader` gets its own contract test (exact-path, ambiguous-basename, traversal/symlink escape, hidden-path rejection) run against a real temp vault directory, mirroring the structure of `FilesystemVaultReaderTest`/`FilesystemCandidateWorkspaceTest`.
3. `FilesystemCandidateWorkspace`'s asset-writing extension is verified by extending its existing test with an assets-present case (staged-then-atomic-move already covered structurally; the new case only needs to confirm asset bytes land at the expected path and survive backup/restore on a failed install).

## Risks / Trade-offs

- **[Risk]** A second `WIKILINK` regex scan (in `AssetResolver`) duplicates pattern-matching structure already in `LinkResolver`, rather than resolving both in one pass. → **Mitigation:** matches the project's established one-concern-per-stage precedent (`MarkdownNormalizer` → `LinkResolver` → `AssetResolver`); the regex and protected-region-skip logic are small and already factored into a reusable `ProtectedRegionScanner`. If a third stage ever needs the same scan shape, extract a shared token-scanning helper then — not preemptively.
- **[Risk]** `EnglishCandidateValidator`'s new asset-route-preservation check (Decision 4) is a scope addition beyond PCM-05's literal text — it's really closing a PCM-06 gap surfaced by this slice. → **Mitigation:** flagged explicitly here and in the low-level plan so the spec-compliance reviewer can weigh it; it reuses an existing, already-tested pattern (`droppedExternalUrls`) rather than inventing new validation machinery.
- **[Risk]** Reading whole asset files into memory (`byte[] content`) for hashing and staged-directory writes is unbounded by file size. → **Mitigation:** accepted for this slice — PCM-05 sets no size limits, and `exporter-java`'s evidence-only `AssetResolver` does the same (`Files.readAllBytes`). Streaming hash/copy is a future concern if large media ever becomes real usage, not a S14 requirement.
- **[Trade-off]** Dedup is scoped to one `prepare` call, not vault-wide. → Accepted: matches PCM-05's scenario text exactly ("multiple accepted references" within one resolution) and avoids inventing a persistent cross-call cache this slice's acceptance boundary never asked for.

## Open Questions

None blocking. The candidate-to-approved asset propagation boundary (Decision 3) is intentionally deferred rather than open — it is out of scope by the Haft problem's declared blast radius, not an unresolved question.
