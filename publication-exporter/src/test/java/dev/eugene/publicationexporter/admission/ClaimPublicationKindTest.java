package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPublicationKindTest {

    private final ClaimPublicationKind admission = new ClaimPublicationKind();

    @Test
    void validClaimCarriesOrderedPublicFieldsAndOpaqueStructuredData() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: latency-budget-is-fiction
                id: 91aa-latency-claim
                title: A fixed latency budget is fiction
                description: A valid description.
                statement: A fixed latency budget is usually the wrong abstraction.
                supports:
                  - label: "Queueing theory: tail latency compounds across hops"
                    target: measuring-tail-latency
                  - label: A "quoted" supporting claim
                opposes:
                  - label: SLA templates assume a single fixed budget
                sources:
                  - attestation: explicit
                    confidence: high
                ---
                Claim body.""");

        AdmittedPublication result = admission.admit(frontmatter);

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(
                PublicationIdentity.of("blog", "claim", "latency-budget-is-fiction"),
                result.identity());
        assertEquals("91aa-latency-claim", result.sourceId());
        assertEquals(
                List.of(
                        PublicField.of("title", "A fixed latency budget is fiction"),
                        PublicField.of("description", "A valid description."),
                        PublicField.of(
                                "statement", "A fixed latency budget is usually the wrong abstraction.")),
                result.fields());
        assertEquals("""
                supports:
                  - label: "Queueing theory: tail latency compounds across hops"
                    target: "measuring-tail-latency"
                  - label: "A \\\"quoted\\\" supporting claim"
                opposes:
                  - label: "SLA templates assume a single fixed budget"
                sources:
                  - attestation: "explicit"
                    confidence: "high"
                """, result.structuredData());
    }

    @Test
    void missingStatementBlocksClaimAdmission() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: missing-statement
                id: 91aa-missing-statement
                title: Missing statement
                description: A valid description.
                ---
                """));

        assertEquals(List.of("statement"), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains("blog/claim"));
    }

    @Test
    void blankStatementBlocksClaimAdmission() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: blank-statement
                id: 91aa-blank-statement
                title: Blank statement
                description: A valid description.
                statement: "   "
                ---
                """));

        assertEquals(List.of("statement"), blockedFields(result));
    }

    @Test
    void relationshipAndSourceArraysRemainOptional() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: no-relationships
                id: 91aa-no-relationships
                title: No relationships
                description: A valid description.
                statement: This claim has no relationship data yet.
                supports: []
                sources: []
                ---
                """));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals("", result.structuredData());
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
