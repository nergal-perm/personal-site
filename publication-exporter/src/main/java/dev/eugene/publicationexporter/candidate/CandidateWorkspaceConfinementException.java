package dev.eugene.publicationexporter.candidate;

import java.nio.file.Path;

public final class CandidateWorkspaceConfinementException extends IllegalStateException {

    CandidateWorkspaceConfinementException(Path candidate, Path resolvedCandidate, Path reviewRoot) {
        super("Candidate directory escapes review root: " + candidate
                + " resolved to " + resolvedCandidate + " outside " + reviewRoot);
    }
}
