## Task 10 Implementation Report

Changed files:
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenance.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenanceWriter.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/fs/TreeHasher.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ReleaseProvenanceWriterTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/fs/SiteWriterTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `site/scripts/check-content.mjs`
- `site/package.json`
- `site/tests/release-provenance.test.mjs`
- `site/tests/task4-content-boundaries.test.mjs`

Red evidence:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest test` initially failed compilation after adding the provenance tests/writer wiring:
  `ReleaseProvenanceException` could not extend final `SiteWriter.WriterException`, and `SiteWriter.stageSite(..., null)` was ambiguous.
- The next focused Maven run failed because `.astro-export` had been added to managed roots but was not created during staging.
- `node --test tests/release-provenance.test.mjs tests/task4-content-boundaries.test.mjs` initially failed because Task 4 disposable build fixtures had no release manifest under the new `npm run build` provenance requirement.

Green evidence:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest test`: passed.
- `node --test tests/release-provenance.test.mjs`: passed.
- `node --test tests/release-provenance.test.mjs tests/task4-content-boundaries.test.mjs`: passed.

Final verification:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest,AstroExportCommandTest test`: passed. JVM emitted the existing JNA native-access warning.
- `node --test tests/release-provenance.test.mjs tests/task4-content-boundaries.test.mjs`: passed, 28/28 tests.
- `npm run check`: passed, `Content validation passed successfully!`.
- `git diff --check`: passed.

Concerns:
- Red-first was not perfectly clean: I wrote a first implementation slice before capturing the intended failing test command. The subsequent failures were real red evidence from the new tests/wiring, and all required checks are green.
- Existing Task 4 disposable fixtures had stale assumptions around `now` and home album rendering. I kept product routes unchanged, generated `now.astro` only inside disposable fixtures, and narrowed stale home assertions to existing generated surfaces.

## Fix Round 1 Report

Changed files:
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenanceWriter.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ReleaseProvenanceWriterTest.java`
- `task-10-report.md`

Red evidence:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,AstroExportCommandTest test` failed to compile before production fixes because `ReleaseProvenanceWriter.ManifestSink`, `ReleaseProvenanceWriter(ManifestSink)`, and `verify(Path, MaterializedRelease)` did not exist.

Green evidence:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,AstroExportCommandTest test`: passed with the existing JNA native-access warning.

Final verification:
- `mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest,AstroExportCommandTest test`: passed with the existing JNA native-access warning.
- `node --test tests/release-provenance.test.mjs tests/task4-content-boundaries.test.mjs`: passed, 28/28 tests.
- `npm run check`: passed, `Content validation passed successfully!`.
- `git diff --check`: passed.

Concerns:
- None beyond the existing JNA warning in the Maven test JVM.
