# SDD Task 4 Report (s17a-blog-note-kind)

## Completed

- Added `NotePublicationKind` in
  `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/NotePublicationKind.java`.
- Registered note kind in
  `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java` so `installed()` now returns both essay and note kinds.
- Added note fixture set and kind-level test mirroring essay structure:
  - `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/NotePublicationKindFixture.java`
  - `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/NotePublicationKindFixtures.java`
  - `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/NotePublicationKindTest.java`
- Kept `BlogNoteAcceptanceTest` on-path and fixed the constructor-style issue by using `CandidateWorkspace.createNull()` in
  `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/BlogNoteAcceptanceTest.java`.
- Adjusted `BlogNoteAcceptanceTest` workflow status editors so prepare and mark-reviewed steps use separate editors compatible with null editor behavior.
- Updated `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java` to validate both essay and note contracts from `write-publication-contract`.

## Verification

- Ran from `publication-exporter/`: `mvn -q -o test`
- Result: PASS (all tests green, 563 tests).
