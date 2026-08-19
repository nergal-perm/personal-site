package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class NullMigrationWorkspace implements MigrationWorkspace {

    private final Set<Integer> applied = new HashSet<>();
    private Optional<MigrationPreimage> preimage = Optional.empty();
    private int interruptionStep = -1;
    private boolean interruptAfterApply;
    private Optional<MigrationPreimage> configuredPreimage = Optional.empty();
    private Optional<MigrationPreimage> restored = Optional.empty();
    private Optional<MigrationGeneration> capturedGeneration = Optional.empty();
    private int captures;
    private int restoredPreimages;
    private int applications;
    private boolean tampered;

    public NullMigrationWorkspace() {
    }

    public NullMigrationWorkspace(int interruptionStep) {
        this(interruptionStep, false);
    }

    public NullMigrationWorkspace(int interruptionStep, boolean interruptAfterApply) {
        this(null, interruptionStep, interruptAfterApply);
    }

    public NullMigrationWorkspace(MigrationPreimage configured, int interruptionStep, boolean interruptAfterApply) {
        if (interruptionStep < 0) {
            throw new IllegalArgumentException("interruptionStep must not be negative");
        }
        if (configured != null) {
            configuredPreimage = Optional.of(configured);
        }
        this.interruptionStep = interruptionStep;
        this.interruptAfterApply = interruptAfterApply;
    }

    public NullMigrationWorkspace(MigrationPreimage configured) {
        configuredPreimage = Optional.of(Objects.requireNonNull(configured, "configured"));
    }

    @Override
    public MigrationPreimage capture(MigrationGeneration generation) {
        MigrationGeneration requested = Objects.requireNonNull(generation, "generation");
        if (capturedGeneration.isPresent() && !sameGeneration(capturedGeneration.get(), requested)) {
            applied.clear();
        }
        MigrationPreimage source = configuredPreimage.orElseGet(
                () -> defaultPreimage(generation));
        MigrationPreimage captured = new MigrationPreimage(generation,
                source.candidateSnapshots(), source.approvedSnapshots(), source.candidateAssets());
        preimage = Optional.of(captured);
        capturedGeneration = Optional.of(requested);
        captures += 1;
        return captured;
    }

    private static boolean sameGeneration(MigrationGeneration first, MigrationGeneration second) {
        return first.inventorySha256().equals(second.inventorySha256())
                && first.identities().equals(second.identities());
    }

    private static MigrationPreimage defaultPreimage(MigrationGeneration generation) {
        Map<PublicationIdentity, CandidateSnapshot> snapshots = generation.identities().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        identity -> identity, NullMigrationWorkspace::defaultSnapshot));
        return new MigrationPreimage(generation, snapshots, snapshots);
    }

    private static CandidateSnapshot defaultSnapshot(PublicationIdentity identity) {
        String fields = dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(java.util.List.of());
        ReferenceMap reference = ReferenceMap.of(identity, "nulled-migration-" + identity.publicId(),
                ContentHash.sha256Hex(""), ContentHash.sha256Hex(""),
                ContentHash.sha256Hex(fields), ContentHash.sha256Hex(fields),
                ContentHash.sha256Hex(""), java.util.List.of());
        return CandidateSnapshot.of("", "", java.util.List.of(), java.util.List.of(), "", reference);
    }

    @Override
    public void apply(MigrationGeneration generation, MigrationPreimage preimage, int step) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(preimage, "preimage");
        if (!preimage.belongsTo(generation)) {
            throw new MigrationRecoveryException("Nulled migration preimage does not belong to its generation.");
        }
        if (step < 0 || step >= generation.identities().size()) {
            throw new IllegalArgumentException("Migration step is outside the generation");
        }
        if (applied.contains(step)) {
            return;
        }
        if (step == interruptionStep && !interruptAfterApply) {
            throw new MigrationInterruptionException("Injected migration interruption at step " + step);
        }
        applied.add(step);
        applications += 1;
        if (step == interruptionStep) {
            throw new MigrationInterruptionException("Injected migration interruption after step " + step);
        }
    }

    @Override
    public void verify(MigrationGeneration generation, MigrationPreimage expected) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(expected, "preimage");
        if (!expected.belongsTo(generation)) {
            throw new MigrationRecoveryException("Nulled migration preimage does not belong to its generation.");
        }
        if (tampered) {
            throw new MigrationRecoveryException("Nulled migration workspace was changed after capture.");
        }
        for (int step = 0; step < generation.identities().size(); step++) {
            boolean expectedApplied = step < generation.completedSteps();
            boolean inFlight = generation.isRunning() && step == generation.completedSteps();
            if (!inFlight && applied.contains(step) != expectedApplied) {
                throw new MigrationRecoveryException(
                        "Nulled migration workspace differs from its journal cursor.");
            }
        }
    }

    @Override
    public void restore(MigrationPreimage recorded) {
        Objects.requireNonNull(recorded, "preimage");
        preimage = Optional.of(recorded);
        restored = Optional.of(recorded);
        applied.clear();
        restoredPreimages += 1;
    }

    public int captures() {
        return captures;
    }

    public Optional<MigrationPreimage> captured() {
        return preimage;
    }

    public Optional<MigrationPreimage> configured() {
        return configuredPreimage;
    }

    public int appliedSteps() {
        return applied.size();
    }

    public int appliedApplications() {
        return applications;
    }

    public int restoredPreimages() {
        return restoredPreimages;
    }

    public Optional<MigrationPreimage> restored() {
        return restored;
    }

    public void resume() {
        interruptionStep = -1;
    }

    public void tamper() {
        tampered = true;
    }
}
