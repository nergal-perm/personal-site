# Astro Export Python To Java Source Map

## Source Modules

| Python file | Java target |
| --- | --- |
| `src/astro_export/cli.py` | `cli/AstroExportCommand.java`, `cli/BridgeResponse.java`, `prepare/PrepareWorkflow.java`, `workflow/WorkflowStateService.java` |
| `src/astro_export/select.py` | `model/Note.java`, `discovery/PublicationDiscovery.java` |
| `src/astro_export/discovery.py` | `discovery/PublicationDiscovery.java`, `process/ProcessRunner.java` |
| `src/astro_export/publication_contract.py` | `model/PublicationKind.java` |
| `src/astro_export/publication_validation.py` | `validation/PublicationValidator.java` |
| `src/astro_export/preflight.py` | `validation/PreflightService.java` |
| `src/astro_export/normalize.py` | `markdown/MarkdownScanner.java`, `manifest/ManifestBuilder.java` |
| `src/astro_export/links.py` | `links/LinkProcessor.java` |
| `src/astro_export/assets.py` | `assets/AssetResolver.java` |
| `src/astro_export/editorial.py` | `editorial/EditorialParser.java` |
| `src/astro_export/manifest.py` | `manifest/ManifestBuilder.java`, `model/ManifestEntry.java`, `model/ManifestResult.java` |
| `src/astro_export/translation_projection.py` | `translation/TranslationProjection.java` |
| `src/astro_export/translation.py` | `translation/TranslationValidator.java` |
| `src/astro_export/review_workspace.py` | `review/ReviewWorkspace.java` |
| `src/astro_export/ru_cache.py` | `review/RuCache.java` |
| `src/astro_export/prepare.py` | `prepare/PrepareWorkflow.java` |
| `src/astro_export/translation_agent.py` | `prepare/TranslationAgent.java` |
| `src/astro_export/codex_runner.py` | `process/CodexRunner.java` |
| `src/astro_export/workflow_state.py` | `frontmatter/WorkflowFrontmatterEditor.java`, `workflow/WorkflowStateService.java`, `fs/AtomicExchange.java` |
| `src/astro_export/writer.py` | `fs/SiteWriter.java` |
| `src/astro_export/report.py` | `report/ReportBuilder.java` |

## Test Modules

The Java test suite must port every behavior assertion from the Python test file with the matching class listed in the implementation plan.
