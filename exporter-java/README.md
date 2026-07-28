# astro-export

Java port of the Astro exporter. The command-line entry point is `astro-export`.

## Development

Run the JVM smoke test suite:

```bash
mvn test
```

Build the native executable with GraalVM Native Image:

```bash
mvn -Pnative native:compile
target/astro-export --help
```

The Python exporter at `/Users/eugene/Documents/personal-wiki/tools/astro-export`
remains the behavioral reference while the Java implementation is migrated.

## Approved translation baseline

`astro-export mark-reviewed` is the only command that advances:

`review/<collection>/<publicId>/published/{ru.md,en.md}`

The command saves one validated page pair after English review approval and
returns success only after the pair is durable. `prepare` uses the Russian
snapshot for its next source diff. Export, `build-from-review`, Astro build,
preview, and deployment never change this baseline.

If approval reports `published-snapshot`, the English/source approval may
already be durable but the prior baseline was preserved. Run **Mark current
translation reviewed** again; retry is idempotent.
