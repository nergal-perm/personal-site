# Final Fix Wave Report

Changed files:
- Migration inventory resolves legacy two-file snapshots from unique live vault `publicCollection`/`publicId` metadata and uses a reconciled in-memory catalog.
- Migration apply stages a first reconciled catalog, journals catalog presence and legacy snapshot hashes, and rolls back only when every legacy snapshot is recoverable and verified.
- Catalog reconciliation allocates distinct new refs; the planner reuses exact positional duplicate occurrences; Node provenance sorting now matches Java natural string ordering.
- Review-build scripts default to this repository's `site` root; removed trailing whitespace.

Tests:
- `mvn -q -Dtest=ReferenceMigrationInventoryTest,SemanticMigrationServiceTest,VaultReferenceCatalogTest,SemanticReferencePlannerTest test`
- `mvn -q test`
- `node --test tests/release-provenance.test.mjs`
- `npm run test:body-first`
- `npm run check`
- `npm run build` intentionally stopped at the production provenance gate because this workspace has no `.astro-export/release-provenance.json`.

Concerns:
- No live migration or deployment was performed. A completed migration whose cleanup has removed legacy snapshots intentionally remains fail-closed on rollback; production builds require a materialized release manifest.
