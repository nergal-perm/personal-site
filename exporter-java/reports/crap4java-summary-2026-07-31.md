# CRAP Summary - exporter-java - 2026-07-31

Full report: reports/crap4java-2026-07-31.txt

## Totals

- Numeric methods: 944
- CRAP > 8.0: 144
- CRAP > 20.0: 35
- CRAP > 50.0: 12
- Max: dev.eugene.astroexport.cli.AstroExportCommand.markReviewed = 330.0 (CC 72, coverage 63.2%)

## Top Methods

```text
   330.0  CC=72  Cov=63.2%   markReviewed                        dev.eugene.astroexport.cli.AstroExportCommand
   182.0  CC=13  Cov=0.0%    latestJobState                      dev.eugene.astroexport.cli.AstroExportCommand
   128.6  CC=29  Cov=50.9%   migrateSemanticLinks                dev.eugene.astroexport.cli.AstroExportCommand
   110.0  CC=10  Cov=0.0%    diagnosticFromExclusion             dev.eugene.astroexport.cli.CommandServices
    90.0  CC=51  Cov=75.3%   prepare                             dev.eugene.astroexport.prepare.PrepareWorkflow
    87.4  CC=23  Cov=50.4%   json                                dev.eugene.astroexport.fs.SiteWriter
    72.0  CC=8   Cov=0.0%    renderHomeCurrent                   dev.eugene.astroexport.prepare.PrepareWorkflow
    68.1  CC=12  Cov=26.9%   rollForwardCatalog                  dev.eugene.astroexport.migration.SemanticMigrationService
    63.4  CC=22  Cov=55.9%   refresh                             dev.eugene.astroexport.cli.AstroExportCommand
    56.0  CC=7   Cov=0.0%    approvedBody                        dev.eugene.astroexport.migration.SemanticMigrationService
    53.6  CC=9   Cov=18.0%   identityFromPreflight               dev.eugene.astroexport.cli.AstroExportCommand
    52.8  CC=12  Cov=34.3%   requiredInt                         dev.eugene.astroexport.references.PageReferenceMapCodec
    49.2  CC=8   Cov=13.6%   reviewText                          dev.eugene.astroexport.review.ReviewWorkspace
    41.5  CC=20  Cov=62.3%   runExport                           dev.eugene.astroexport.cli.AstroExportCommand
    36.8  CC=21  Cov=67.1%   installEnglish                      dev.eugene.astroexport.prepare.PrepareWorkflow
    34.0  CC=8   Cov=25.9%   yamlList                            dev.eugene.astroexport.fs.SiteWriter
    33.6  CC=15  Cov=56.4%   draftProjection                     dev.eugene.astroexport.prepare.PrepareWorkflow
    32.3  CC=31  Cov=88.8%   replaceManagedTrees                 dev.eugene.astroexport.fs.SiteWriter
    31.7  CC=15  Cov=57.9%   candidateTemplate                   dev.eugene.astroexport.prepare.PrepareWorkflow
    30.3  CC=23  Cov=76.0%   parseCurrent                        dev.eugene.astroexport.review.ReviewWorkspace
    30.0  CC=5   Cov=0.0%    deletePathConfined                  dev.eugene.astroexport.fs.SiteWriter
    29.7  CC=13  Cov=53.7%   rollbackCatalog                     dev.eugene.astroexport.migration.SemanticMigrationService
    29.5  CC=6   Cov=13.2%   decodeNamedEntity                   dev.eugene.astroexport.manifest.ManifestBuilder
    29.2  CC=10  Cov=42.4%   numericJsonMapKey                   dev.eugene.astroexport.manifest.ManifestBuilder
    27.9  CC=27  Cov=89.4%   validatedTarget                     dev.eugene.astroexport.fs.SiteWriter
```

## Top Classes By Maximum Method CRAP

```text
   330.0  sum=  1085.5 high20=6   methods=71  dev.eugene.astroexport.cli.AstroExportCommand
   110.0  sum=   191.9 high20=2   methods=37  dev.eugene.astroexport.cli.CommandServices
    90.0  sum=   535.6 high20=6   methods=68  dev.eugene.astroexport.prepare.PrepareWorkflow
    87.4  sum=   531.9 high20=5   methods=90  dev.eugene.astroexport.fs.SiteWriter
    68.1  sum=   378.0 high20=4   methods=54  dev.eugene.astroexport.migration.SemanticMigrationService
    52.8  sum=   113.0 high20=1   methods=16  dev.eugene.astroexport.references.PageReferenceMapCodec
    49.2  sum=   339.0 high20=3   methods=59  dev.eugene.astroexport.review.ReviewWorkspace
    29.5  sum=   518.0 high20=3   methods=74  dev.eugene.astroexport.manifest.ManifestBuilder
    24.8  sum=    59.9 high20=1   methods=16  dev.eugene.astroexport.review.ReviewLaunchPlanner
    22.9  sum=   145.3 high20=1   methods=25  dev.eugene.astroexport.translation.TranslationValidator
    22.1  sum=    65.3 high20=1   methods=11  dev.eugene.astroexport.review.RuCache
    22.0  sum=   182.1 high20=1   methods=40  dev.eugene.astroexport.migration.ReferenceMigrationAligner
    21.0  sum=    40.0 high20=1   methods=8   dev.eugene.astroexport.validation.PublicationValidator
    19.4  sum=   114.4 high20=0   methods=22  dev.eugene.astroexport.editorial.EditorialParser
    16.1  sum=    62.6 high20=0   methods=14  dev.eugene.astroexport.review.PublishedSnapshotStore
    15.3  sum=    50.5 high20=0   methods=17  dev.eugene.astroexport.workflow.WorkflowStateService
    15.0  sum=    51.2 high20=0   methods=11  dev.eugene.astroexport.migration.SemanticSchemaState
    13.5  sum=    16.5 high20=0   methods=2   dev.eugene.astroexport.fs.JnaAtomicExchange
    13.2  sum=    95.1 high20=0   methods=16  dev.eugene.astroexport.frontmatter.WorkflowFrontmatterEditor
    13.2  sum=    70.6 high20=0   methods=16  dev.eugene.astroexport.assets.AssetResolver
```
