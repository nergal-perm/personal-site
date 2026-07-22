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
