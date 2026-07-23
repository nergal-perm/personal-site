# Cutover Checklist

- [x] Python source status captured with `git status --short /Users/eugene/Documents/personal-wiki/tools/astro-export`.
  Evidence captured on 2026-07-23: Python oracle tree was dirty, including modified `tools/astro-export/src/astro_export/cli.py`, `publication_contract.py`, `workflow_state.py`, `tests/test_cli.py`, `README.md`, multiple review files, and four untracked review directories.
- [x] Java `mvn test` passes.
  Evidence command: `env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn test`.
- [x] Java `mvn -Pnative native:compile` passes.
  Evidence command: `env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn -Pnative native:compile`.
- [x] Native dry-run report matches Python oracle counts and mappings.
  Live shared-review blocked reports are byte-identical at `/private/tmp/astro-export-python-dry-run.md` and `/private/tmp/astro-export-java-dry-run-python-review.md`; the live blocker prevents success-only mapping sections, so success mapping parity is covered by the fixture report diff in `docs/native-build.md`.
- [x] Native temp write produces managed-tree hashes matching Python oracle.
  Evidence root: `/private/tmp/astro-export-task12-h_l8dkoy`; Python and native Java write reports are byte-identical and the three managed roots have clean recursive diffs.
- [x] Current operator scripts have Java equivalents.
  Evidence: `scripts/export-site.sh`, `scripts/build-from-review.sh`, `scripts/build-astro-site.sh`, and `scripts/migrate-overrides.sh` invoke `target/astro-export` when present and fall back to Maven exec in development.
- [x] No production Astro deployment is included in cutover.
  Evidence: all write-mode parity commands used `/private/tmp/astro-export-task12-h_l8dkoy/*-astro`; no command wrote to or deployed `/Users/eugene/POS/software-dev/astro-blog`.

## Remaining Cutover Notes

- Keep the Python `uv run astro-export` workflow available until the operator explicitly approves replacement.
- The current live Python review workspace is stale for `bibliography/2025/The Lean Startup.md` / `book-the-lean-startup`; this is an oracle state blocker, not a native-image blocker.
- The Java default `review/` path is repo-local. For parity with the current Python checkout before review migration, pass `--review /Users/eugene/Documents/personal-wiki/tools/astro-export/review`.
