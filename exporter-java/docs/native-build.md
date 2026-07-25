# Native Build

Task 12 native evidence was captured on 2026-07-23 with GraalVM:

```bash
JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal
PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin
```

## Metadata

Native metadata lives under:

```text
src/main/resources/META-INF/native-image/dev.eugene/astro-export/
```

The metadata was generated with the tracing agent:

```bash
env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn -Pnative -DskipTests package
env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/dev.eugene/astro-export -cp target/classes:$(cat target/classpath.txt) dev.eugene.astroexport.AstroExportApp --help
env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/dev.eugene/astro-export -cp target/classes:$(cat target/classpath.txt) dev.eugene.astroexport.AstroExportApp --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-native-dry-run.md
```

Generated files:

```text
src/main/resources/META-INF/native-image/dev.eugene/astro-export/reachability-metadata.json
src/main/resources/META-INF/native-image/dev.eugene/astro-export/resource-config.json
```

`resource-config.json` keeps the packaged search templates and the line-break iterator resource traced during the live dry-run.
`reachability-metadata.json` also explicitly lists every picocli root and subcommand field annotated with `@Option`, `@Spec`, or `@ParentCommand`. The tracing-agent help/root dry-run flow does not visit all subcommand option bindings, so this manual completion is required for native CLI parity.

## Build

Release build command:

```bash
env JAVA_HOME=/Users/eugene/.sdkman/candidates/java/25.0.4-graal PATH=/Users/eugene/.sdkman/candidates/java/25.0.4-graal/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn -Pnative native:compile
```

Evidence:

```text
target/astro-export: executable, 32,420,528 bytes
native-image: BUILD SUCCESS
```

Smoke commands:

```bash
target/astro-export --help
target/astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-dry-run.md
target/astro-export prepare --vault /private/tmp/astro-export-java-missing-vault --note missing.md --json
target/astro-export inspect-publication --vault /private/tmp/astro-export-java-missing-vault --note missing.md --json
target/astro-export mark-reviewed --vault /private/tmp/astro-export-java-missing-vault --note missing.md --json
target/astro-export refresh-publication-queue --vault /private/tmp/astro-export-java-missing-vault --json
```

Results:

```text
--help exit code: 0
default native dry-run exit code: 1
default native dry-run report: /private/tmp/astro-export-java-dry-run.md
prepare missing-vault JSON probe exit code: 1, status: metadata_blocked, diagnostics: 1, no MissingReflectionRegistrationError
inspect-publication missing-vault JSON probe exit code: 1, status: metadata_blocked, diagnostics: 1, no MissingReflectionRegistrationError
mark-reviewed missing-vault JSON probe exit code: 1, status: metadata_blocked, diagnostics: 1, no MissingReflectionRegistrationError
refresh-publication-queue missing-vault JSON probe exit code: 1, status: refresh_failed, diagnostics: 1, no MissingReflectionRegistrationError
```

The default Java dry-run uses the Java repo-local `review/` directory, which is empty in this checkout. For oracle parity, the Java run was pointed at the same review workspace as the Python exporter:

```bash
cd /Users/eugene/Documents/personal-wiki/tools/astro-export
uv run astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-python-dry-run.md

cd /Users/eugene/Dev/astro-export-java
target/astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-dry-run-python-review.md --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review
cmp -s /private/tmp/astro-export-python-dry-run.md /private/tmp/astro-export-java-dry-run-python-review.md
```

Shared-review dry-run parity:

```text
exit code: 1 for both
report diff: none
files matched by rg: 29
confirmed publish true: 28
included by selector: 28
excluded by selector: 0
normalized RU records: 28
generated EN records: 0
translation blockers: 1
blocker: bibliography/2025/The Lean Startup.md / book-the-lean-startup stale review
```

The live source currently blocks before success-only sections such as generated EN mappings and collected assets. Success-path source mappings, asset count, and managed-tree hashes were therefore verified with an isolated fixture.

## Fixture Parity

Fixture root:

```text
/private/tmp/astro-export-task12-h_l8dkoy
```

Python oracle command:

```bash
cd /Users/eugene/Documents/personal-wiki/tools/astro-export
uv run astro-export --vault /private/tmp/astro-export-task12-h_l8dkoy/vault --out /private/tmp/astro-export-task12-h_l8dkoy/python-astro --review /private/tmp/astro-export-task12-h_l8dkoy/python-review --report /private/tmp/astro-export-task12-h_l8dkoy/python-write.md
```

Native Java command:

```bash
cd /Users/eugene/Dev/astro-export-java
target/astro-export --vault /private/tmp/astro-export-task12-h_l8dkoy/vault --out /private/tmp/astro-export-task12-h_l8dkoy/java-astro --review /private/tmp/astro-export-task12-h_l8dkoy/java-review --report /private/tmp/astro-export-task12-h_l8dkoy/java-write.md
```

Diff checks:

```bash
cmp -s /private/tmp/astro-export-task12-h_l8dkoy/python-write.md /private/tmp/astro-export-task12-h_l8dkoy/java-write.md
diff -ru /private/tmp/astro-export-task12-h_l8dkoy/python-astro/src/content /private/tmp/astro-export-task12-h_l8dkoy/java-astro/src/content
diff -ru /private/tmp/astro-export-task12-h_l8dkoy/python-astro/src/data/pages /private/tmp/astro-export-task12-h_l8dkoy/java-astro/src/data/pages
diff -ru /private/tmp/astro-export-task12-h_l8dkoy/python-astro/public/assets/vault /private/tmp/astro-export-task12-h_l8dkoy/java-astro/public/assets/vault
```

Fixture parity summary:

```text
exit code: 0 for both
report diff: none
managed-root diffs: none
generated records: 4
resolved assets: 1
selected sources: anywhere/Essay.md -> blog/essay
RU mapping: anywhere/Essay.md -> src/content/blog/ru/essay.md -> /ru/essays/essay/
EN mapping: anywhere/Essay.md -> src/content/blog/en/essay.md -> /en/essays/essay/
```

Managed tree hashes:

```text
public/assets/vault 1608843c085ab62c2735e7dfa3e6114a7811d99fa3b297187216bb42fede9b74
src/content         9594ee115d22de16630549121a487b7524f5ab0e19b7aaacee17a1efee0e59a2
src/data/pages      6ef9d9d93c30a5e8ac1d5e3ca4e15250c669fccbbcd9d15cb8fa8e3f11c17ffb
```

No command in this evidence wrote to `/Users/eugene/POS/software-dev/astro-blog` or deployed production output.
