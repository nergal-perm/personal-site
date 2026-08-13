# Task 5 report — End-to-end slice proof

## Goal

Add final acceptance coverage for the editorial curated-page publication kind: prove the handler-level path from admission through preparation, approval, release, and site installation, and prove the CLI publication contract exposes the curated-page required fields correctly.

## What was built

- Added `CuratedPageAcceptanceTest.aboutPageCompletesAdmissionThroughSiteInstallation()` following the established `ConceptAcceptanceTest` handler-level flow.
- Verified the installed RU and EN JSON files use the current Jackson pretty-printed shape, including translated title/summary values and `searchable: true`.
- Extended `WritePublicationContractCliAcceptanceTest` to assert the `editorial/curated_page` required-field list, `editorialPage` as a string allowed value `about`, and the absence of a `description` requirement.
- Updated Task 5 checkboxes in the OpenSpec task ledger after verification.
- No production code was changed.

## Test counts

- Plan baseline before the Task 1–4 additions: 763 tests.
- Focused Task 5 verification: 137 tests, 0 failures, 0 errors.
- Full verification after Task 5: 801 tests, 0 failures, 0 errors.
- The new acceptance class adds one test; the existing CLI acceptance method was extended without adding another test method.

## Concerns

- The brief’s sample `TranslationWorker.createNull("", ...)` could not pass the current validator because the curated-page source body is necessarily nonblank. The test uses a nonblank English body; that body is not projected into the curated-page JSON, while all translated public fields remain exactly as specified.
- `graphify update .` was attempted after the test changes but failed with macOS `Operation not permitted` while rebuilding; no graph refresh claim is made.

## Verification commands

```text
mvn -f publication-exporter/pom.xml test -Dtest=AboutPageBodyTest,CuratedPagePublicationKindTest,PublicationContractConformanceTest,FilesystemManagedSiteInstallerTest,EnglishCandidateValidatorTest,CuratedPageAcceptanceTest,WritePublicationContractCliAcceptanceTest
mvn -f publication-exporter/pom.xml test
```

## Review-fix report

- Refactored `CuratedPageAcceptanceTest.aboutPageCompletesAdmissionThroughSiteInstallation()` into a short composed scenario with named helpers for fixture assembly, preparation, approval, release, installation, and installed-file readback.
- Replaced substring checks with Jackson parsing of both installed `ru/about.json` and `en/about.json` files.
- Added assertions for every curated-page field in both locales: `title`, `summary`, `eyebrow`, `lead`, the nested `principles[0].title` and `principles[0].text` pair, and `colophon`; retained `searchable` and added explicit `id`, `type`, `contentType`, and locale checks.
- `WritePublicationContractCliAcceptanceTest.java` was not modified.
- Test count before this fix: 801 tests, based on the previously verified Task 5 baseline. After this fix: 801 tests, 0 failures, 0 errors (`mvn -f publication-exporter/pom.xml test`).
- `graphify update .` was attempted but could not refresh the graph because macOS returned `Operation not permitted` during rebuild.
