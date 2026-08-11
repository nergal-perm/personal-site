# Software System Spec

## SS.publication-pipeline.001 Reviewed-content publication structure

```yaml spec-section
id: SS.publication-pipeline.001
spec: software-system
kind: software.selected_structure
title: Plugin, exporter, review baseline, and site are separate file-mediated components
statement_type: explanation
claim_layer: description
owner: human
status: draft
valid_until: "2027-08-02T00:00:00Z"
depends_on: []
supersedes: []
terms: [approved translation baseline, review manifest]
target_refs: [README.md, obsidian-plugin/manifest.json, publication-exporter/pom.xml, site/package.json]
evidence_required: []
claims:
  - id: SS.publication-pipeline.001.D1
    class: D
    statement: "The Obsidian plugin invokes publication-exporter as a local CLI subprocess; the exporter writes and reads the reviewed RU/EN baseline on disk; and the Astro site consumes that approved baseline when producing publishable pages."
    scope: [obsidian-plugin, publication-exporter, review baseline, site]
```

This software-structure claim retains only the current component and artifact boundary from ES.method.001. It intentionally does not prescribe staffing, review practice, test commands, CI, release, or deployment policy.
