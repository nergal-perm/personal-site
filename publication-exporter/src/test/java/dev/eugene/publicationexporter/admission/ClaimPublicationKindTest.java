package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPublicationKindTest {

    private final ClaimPublicationKind admission = new ClaimPublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.ClaimPublicationKindFixtures#all")
    void admitsOrBlocksPerFixture(ClaimPublicationKindFixture fixture) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(fixture.noteSource()));

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(fixture.expectedBlockedFields(), blockedFields(result), fixture.name());
        }
    }

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
                    target: "urn:claim:measuring-tail-latency"
                  - label: A "quoted" supporting claim
                opposes:
                  - label: SLA templates assume a single fixed budget
                sources:
                  - link:
                      label: Queueing theory
                      target: measuring-tail-latency
                    attestation: explicit
                    evidence:
                      - kind: text
                        value: Tail latency compounds.
                      - kind: reference
                        target: measuring-tail-latency
                    locator:
                      - kind: text
                        value: Section 3
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
                    target: "urn:claim:measuring-tail-latency"
                  - label: "A \\\"quoted\\\" supporting claim"
                opposes:
                  - label: "SLA templates assume a single fixed budget"
                sources:
                  - link:
                      label: Queueing theory
                      target: measuring-tail-latency
                    attestation: explicit
                    evidence:
                      - kind: text
                        value: Tail latency compounds.
                      - kind: reference
                        target: measuring-tail-latency
                    locator:
                      - kind: text
                        value: Section 3
                    confidence: high
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

    @Test
    void nonListRelationshipMetadataBlocksClaimAdmission() {
        AdmittedPublication result = admitClaimWith("supports: measuring-tail-latency");

        assertStructuredFieldBlocked(result, "supports", "list");
    }

    @Test
    void nonListSourceMetadataBlocksClaimAdmission() {
        AdmittedPublication result = admitClaimWith("""
                sources:
                  link:
                    label: Queueing theory
                """);

        assertStructuredFieldBlocked(result, "sources", "list");
    }

    @Test
    void inlineMappingSourceMetadataBlocksClaimAdmission() {
        AdmittedPublication result = admitClaimWith("sources: {attestation: explicit}");

        assertStructuredFieldBlocked(result, "sources", "list");
    }

    @Test
    void siteShapedSourceMetadataRemainsOpaque() {
        String sources = """
                sources:
                  - link:
                      label: Queueing theory
                      target: measuring-tail-latency
                    attestation: explicit
                    evidence: Tail latency compounds across hops.
                    locator:
                      - kind: text
                        value: Section 3
                      - kind: reference
                        target: measuring-tail-latency
                    confidence: high
                """;

        AdmittedPublication result = admitClaimWith(sources);

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(sources, result.structuredData());
    }

    @Test
    void sourceLinkMustBeReferenceObject() {
        AdmittedPublication result = admitClaimWith("""
                sources:
                  - link: measuring-tail-latency
                """);

        assertStructuredFieldBlocked(result, "sources", "site claimSource shape");
    }

    @Test
    void sourceEntriesRejectUndeclaredFields() {
        AdmittedPublication result = admitClaimWith("""
                sources:
                  - attestation: explicit
                    privateNote: must-not-be-projected
                """);

        assertStructuredFieldBlocked(result, "sources", "site claimSource shape");
    }

    @Test
    void sourceRichTextTokensRejectUndeclaredFields() {
        AdmittedPublication result = admitClaimWith("""
                sources:
                  - evidence:
                      - kind: text
                        value: Tail latency compounds.
                        privateNote: must-not-be-projected
                """);

        assertStructuredFieldBlocked(result, "sources", "site claimSource shape");
    }

    @Test
    void sourceScalarsRejectYamlTags() {
        AdmittedPublication result = admitClaimWith("""
                sources:
                  - attestation: !!int 1
                    confidence: !tag high
                """);

        assertStructuredFieldBlocked(result, "sources", "site claimSource shape");
    }

    @Test
    void relationshipEntriesRequireLabels() {
        AdmittedPublication result = admitClaimWith("""
                supports:
                  - target: measuring-tail-latency
                """);

        assertStructuredFieldBlocked(result, "supports", "label");
    }

    @Test
    void relationshipEntriesRequireNonBlankLabels() {
        AdmittedPublication result = admitClaimWith("""
                supports:
                  - label: "   "
                    target: measuring-tail-latency
                """);

        assertStructuredFieldBlocked(result, "supports", "label");
    }

    @Test
    void relationshipEntriesRejectUndeclaredKeys() {
        AdmittedPublication result = admitClaimWith("""
                supports:
                  - label: Queueing theory
                    privateNote: must-not-be-projected
                """);

        assertStructuredFieldBlocked(result, "supports", "label and optional target");
    }

    @Test
    void relationshipEntriesRejectStructuredValues() {
        AdmittedPublication result = admitClaimWith("""
                supports:
                  - label:
                      text: Queueing theory
                    target: measuring-tail-latency
                """);

        assertStructuredFieldBlocked(result, "supports", "label and optional target");
    }

    private AdmittedPublication admitClaimWith(String structuredMetadata) {
        String note = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: structured-metadata
                id: 91aa-structured-metadata
                title: Structured metadata
                description: A valid description.
                statement: A valid claim statement.
                """ + structuredMetadata.stripTrailing() + """

                ---
                """;
        return admission.admit(MarkdownNote.parse(note));
    }

    private static void assertStructuredFieldBlocked(
            AdmittedPublication result, String field, String expectedMessagePart) {
        assertEquals(List.of(field), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains("blog/claim"));
        assertTrue(result.diagnostics().get(0).message().contains(expectedMessagePart));
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
