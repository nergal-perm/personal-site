# Enabling System Spec

## ES.creator-role.001 Solo author operates all three subsystems and is the only reviewer

```yaml spec-section
id: ES.creator-role.001
spec: enabling-system
statement: "Evgenii Terekhov is the sole author, operator, and reviewer across obsidian-plugin, exporter-java, and site (single committer in git log, personal-use README, DEPLOY.md aimed at one running Obsidian instance). There is no separate reviewer role or team process: the human-in-the-loop review gates (Zed diff review, mark-reviewed approval) are performed by the same person who authors the vault notes and the code."
kind: creator-role
title: Single-operator team, no separate reviewer role
statement_type: explanation
claim_layer: carrier
owner: human
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: []
supersedes: [ES.placeholder.001]
terms: []
target_refs: [README.md, obsidian-plugin/DEPLOY.md]
evidence_required: []
```

Approved by operator 2026-08-02, confirmed: single operator for the whole stack, no other contributor or reviewer.

## ES.method.001 Three independently-buildable/testable tools, glued by CLI + file-based manifests

```yaml spec-section
id: ES.method.001
spec: enabling-system
statement: "The enabling system is three separately built/tested tools communicating through files, not a shared runtime: obsidian-plugin (TypeScript, tested under obsidian-plugin/tests, invoked from inside a running Obsidian app) shells out to the exporter-java CLI (Maven/Java 21, `mvn test`, optionally compiled to a GraalVM native binary) as a subprocess; the exporter writes/reads review/ manifests and review baselines on disk; site (Astro 7, Node >=22.12) consumes only the approved review/ baseline via scripts/build-from-review.sh and scripts/check-content.mjs. Deployment is automatic: Netlify's git connector watches the remote repo and, on push, builds and deploys the site — but only when the push touches the `site` base directory, since netlify.toml's `ignore` command (`git diff --quiet $CACHED_COMMIT_REF $COMMIT_REF -- .`, evaluated with base=site) skips the build otherwise. Pushing changes to obsidian-plugin or exporter-java alone does not trigger a deploy. There is no CI pipeline (.github/workflows) wiring the three tools together; each tool's own test command is the only automated gate, and Netlify's build is the only automated deploy gate."
kind: enabling.method
title: Three tools glued by CLI subprocess + file-based manifests; Netlify auto-deploys on push, site-path-scoped
statement_type: explanation
claim_layer: carrier
owner: human
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: [ES.creator-role.001]
supersedes: []
terms: [review manifest, build-from-review, content/provenance gate]
target_refs: [README.md, exporter-java/pom.xml, site/package.json, netlify.toml]
evidence_required: []
```

Approved by operator 2026-08-02, who corrected the deploy mechanism: Netlify's connector auto-builds and deploys on push to the remote, scoped to changes under `site/` — not the manual copy-`dist/` process the README describes. README.md's "Deploy is currently manual" line is now known-stale and should be updated separately.
