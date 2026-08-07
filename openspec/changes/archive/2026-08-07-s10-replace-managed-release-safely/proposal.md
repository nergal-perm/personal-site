## Why

S07 made `install-to-site` create-only: `FilesystemManagedSiteInstaller.install(...)` throws
`SiteAlreadyInstalledException` whenever the managed RU/EN markdown destinations already exist. Once an essay
is approved a second time (S09 now makes that possible), there is no way to ever get the changed content onto
the live site — Milestone B's "safe repeated publication" promise stops one step short of the site itself.

## What Changes

- `install-to-site`, given a newly approved snapshot and an already-installed managed generation for the same
  publication, replaces the prior managed RU/EN markdown files and provenance record atomically as one
  coherent generation — never exposing a mixed old/new generation to a concurrent reader (`site/scripts/
  check-content.mjs`, Astro's build, or another `install-to-site` invocation).
- If a declared release input (the selected approved snapshot's hashes) changes between planning and commit,
  the release is blocked and existing live site trees remain unchanged.
- An interruption during replacement (crash, write failure) deterministically recovers to exactly the old
  complete generation or the new complete generation on the next inspection or retry — never a torn/mixed
  state — and reports the recovery outcome rather than silently guessing.
- Concurrent replacement attempts for the same publication identity are serialized so no interleaved/partial
  write is ever observable.
- Building the same approved state twice (no change) continues to produce identical managed content and
  normalized provenance — already true for the first generation, confirmed to still hold for a replacement.
- First-installation behavior (S07, no prior managed generation) is unchanged.

**Out of scope:** semantic target activation (S20's job), additional content kinds beyond `blog/essay`
(S17's job), and legacy migration (S21+'s job).

## Capabilities

### New Capabilities
(none — this slice extends existing capabilities' requirements, it does not introduce a new bounded
capability)

### Modified Capabilities
(none — REL-03, REL-04, and REL-05 are already fully specified in the baseline, including their replace/
recover/tamper-detection scenarios, all explicitly marked "not yet applicable"/"reachable once S10 exists" in
S07's own scope-pins. This slice realizes those scenarios; see `scope-pins.md` for the full analysis.)

## Impact

- `publication-exporter` module: `install-to-site` command/handler (`InstallToSiteHandler`),
  `ManagedSiteInstaller` port and its Filesystem/Null adapters (replace-with-durable-recovery semantics,
  input-drift guard, tamper detection at the provenance boundary), `SiteReleaseManifest` (provenance
  comparison across two generations).
- Reuses proven patterns from prior slices rather than inventing new ones: the backup/restore-with-durable-
  recovery protocol (`FilesystemCandidateWorkspace` in S08, `FilesystemApprovedSnapshotWorkspace` in S09) and
  the `FileChannel.tryLock()` OS advisory cross-process lock (S09's final, hardened design — replacing the
  current `FilesystemManagedSiteInstaller`'s fragile `Files.createFile(...)`-based install lock, the same
  mechanism S09 spent four review rounds hardening).
- Not touched: `prepare`, `inspect-publication`, `mark-reviewed`, `build-from-review`'s `ReleaseOutputStore`,
  `refresh-publication-queue`.
