## 1. Journalled in-memory migration state machine

- [x] 1.1 Add immutable migration generation, step, terminal-state, catalog, and recovery value objects with rejection of invalid fingerprints, duplicate identities, and terminal-state rewrites.
- [x] 1.2 Add null migration workspace, journal store, catalog store, and semantic-operation lock that expose observable state for state-based tests.
- [x] 1.3 Implement explicit all-inventory apply, roll-forward, and roll-back with preflight validation before the first mutation and journal transition after every step.
- [x] 1.4 Prove valid apply, blocker/ambiguity rejection, injected interruption, both recoveries, and competing lock collision through the in-memory fixture.

## 2. Filesystem migration adapters

- [x] 2.1 Add strict JSON codec and atomic filesystem stores for migration journal, catalog, and write-capable activation marker.
- [x] 2.2 Add a filesystem semantic-operation lock with non-blocking collision failure and a scoped release lifetime.
- [x] 2.3 Add confined filesystem migration workspace behavior that captures preimages and restores them on explicit roll-back.
- [x] 2.4 Prove malformed manifests, duplicate fields, unsafe paths, interrupted cursor persistence, recovery, and lock collision with temporary review roots.

## 3. Activation admission and command boundary

- [x] 3.1 Extend semantic activation checking to require agreeing marker, catalog, sealed journal, identity set, and complete approved triples.
- [x] 3.2 Add an explicitly requested migration-apply/recovery CLI surface that validates a separate human decision file and never treats a draft as approval.
- [x] 3.3 Add one filesystem acceptance fixture covering apply to sealed state, explicit recovery after interruption, incomplete-state rejection, and unchanged normal greenfield behavior.
- [x] 3.4 Run the legacy-focused tests and full Maven suite, validate the OpenSpec change strictly, and update Graphify after code changes.
