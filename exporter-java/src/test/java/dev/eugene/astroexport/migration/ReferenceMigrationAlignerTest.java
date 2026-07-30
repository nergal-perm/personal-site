package dev.eugene.astroexport.migration;

import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.AMBIGUOUS_TRANSLATION;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.EXACT;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.ORDER_MISMATCH;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.UNRESOLVED_TARGET;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.UNSAFE_INPUT;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.CONFIRMED_NEEDED;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.EXACT_PAGE;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.ORDER_MISMATCH_PAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.references.VaultReferenceResolver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReferenceMigrationAlignerTest {
  private final ReferenceMigrationAligner aligner = new ReferenceMigrationAligner();

  @Test
  void classifiesExactDirectAndStrippedLegacyReferences() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("See [[Public|public]] then [[Private|private]]."),
        approvedRu("See [public](/ru/notes/public/) then private."),
        approvedEn("See [public](/en/notes/public/) then private."),
        resolver(publicTarget(), privateTarget()));

    assertEquals(List.of(EXACT, EXACT), classifications(page));
    assertEquals(EXACT_PAGE, page.status());
    assertTrue(page.automatic());
  }

  @Test
  void classifiesEnglishOrderSwapInsteadOfPairingByOrdinal() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]] [[C|two]]"),
        approvedRu("[one](/ru/b/) [two](/ru/c/)"),
        approvedEn("[two](/en/c/) [one](/en/b/)"),
        resolver(targetB(), targetC()));

    assertEquals(ORDER_MISMATCH_PAGE, page.status());
    assertEquals(List.of(ORDER_MISMATCH, ORDER_MISMATCH), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void duplicateTargetsAndVisibleLabelsRequireConfirmation() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|same]] [[B|same]]"),
        approvedRu("[same](/ru/b/) [same](/ru/b/)"),
        approvedEn("[same](/en/b/) [same](/en/b/)"),
        resolver(targetB()));

    assertEquals(CONFIRMED_NEEDED, page.status());
    assertEquals(List.of(AMBIGUOUS_TRANSLATION, AMBIGUOUS_TRANSLATION), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void sameTargetDifferentLabelsReversedInEnglishIsOrderMismatch() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|first]] [[B|second]]"),
        approvedRu("[first](/ru/b/) [second](/ru/b/)"),
        approvedEn("[second](/en/b/) [first](/en/b/)"),
        resolver(targetB()));

    assertEquals(ORDER_MISMATCH_PAGE, page.status());
    assertEquals(List.of(ORDER_MISMATCH, ORDER_MISMATCH), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void unresolvedRawTargetIsNotProposedAsSemanticTriple() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[Missing|missing]]"),
        approvedRu("missing"),
        approvedEn("missing"),
        resolver(targetB()));

    assertEquals(List.of(UNRESOLVED_TARGET), classifications(page));
    assertEquals(null, page.occurrences().getFirst().proposedReference());
    assertFalse(page.automatic());
  }

  @Test
  void unresolvedRawTargetDoesNotPreventExactResolvedAssignments() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]] then [[Missing|missing]]"),
        approvedRu("[one](/ru/b/) then missing"),
        approvedEn("[one](/en/b/) then missing"),
        resolver(targetB()));

    assertEquals(List.of(EXACT, UNRESOLVED_TARGET), classifications(page));
    assertEquals("ref-0001", page.occurrences().getFirst().proposedReferenceId());
    assertEquals(null, page.occurrences().get(1).proposedReference());
    assertFalse(page.automatic());
  }

  @Test
  void multiplePossibleEnglishSpansAreAmbiguous() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]]"),
        approvedRu("[one](/ru/b/)"),
        approvedEn("[one](/en/b/) and [one](/en/b/)"),
        resolver(targetB()));

    assertEquals(List.of(AMBIGUOUS_TRANSLATION), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void monotonicAssignmentCanResolveExtraEnglishCandidates() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]] between [[C|two]]"),
        approvedRu("[one](/ru/b/) between [two](/ru/c/)"),
        approvedEn("[one](/en/b/) between [two](/en/c/) and [one](/en/b/)"),
        resolver(targetB(), targetC()));

    assertEquals(EXACT_PAGE, page.status());
    assertEquals(List.of(EXACT, EXACT), classifications(page));
    assertTrue(page.automatic());
  }

  @Test
  void monotonicAssignmentUsesPageLevelDynamicProgrammingForCandidateOrder() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]] [[C|two]] [[D|three]]"),
        approvedRu("[one](/ru/b/) [two](/ru/c/) [three](/ru/d/)"),
        approvedEn("""
            [two](/en/c/) stray [three](/en/d/) stray [one](/en/b/)
            [two](/en/c/) [three](/en/d/)
            """),
        resolver(targetB(), targetC(), targetD()));

    assertEquals(EXACT_PAGE, page.status());
    assertEquals(List.of(EXACT, EXACT, EXACT), classifications(page));
    assertTrue(page.automatic());
  }

  @Test
  void doesNotInferEnglishOccurrenceFromPlainTranslatedLabelOnly() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]]"),
        approvedRu("[one](/ru/b/)"),
        approvedEn("one"),
        resolver(targetB()));

    assertEquals(List.of(AMBIGUOUS_TRANSLATION), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void protectedContextsDoNotContributeCandidateSpans() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("`[[B|one]]` [[B|one]]"),
        approvedRu("`[one](/ru/b/)` [one](/ru/b/)"),
        approvedEn("`[one](/en/b/)` [one](/en/b/)"),
        resolver(targetB()));

    assertEquals(1, page.occurrences().size());
    assertEquals(EXACT, page.occurrences().getFirst().classification());
    assertEquals("`[one](/en/b/)` [one](/en/b/)", page.approvedEnglish().text());
  }

  @Test
  void partialLegacyPairIsUnsafeInput() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]]"),
        approvedRu("[one](/ru/b/)"),
        ReferenceMigrationAligner.ApprovedDocument.unsafe("page/en.md", "missing approved English"),
        resolver(targetB()));

    assertEquals(List.of(UNSAFE_INPUT), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void staleCurrentSourceContextIsUnsafeInput() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("Changed before [[B|one]] after."),
        approvedRu("Old before [one](/ru/b/) after."),
        approvedEn("Old before [one](/en/b/) after."),
        resolver(targetB()));

    assertEquals(List.of(UNSAFE_INPUT), classifications(page));
    assertFalse(page.automatic());
  }

  @Test
  void legacyRouteThatNoLongerMatchesCurrentTargetRouteNeedsConfirmation() {
    ReferenceMigrationAligner.MigrationPage page = aligner.align(
        raw("[[B|one]]"),
        approvedRu("[one](/ru/old-b/)"),
        approvedEn("[one](/en/old-b/)"),
        resolver(targetB()));

    assertEquals(List.of(AMBIGUOUS_TRANSLATION), classifications(page));
    assertFalse(page.automatic());
  }

  private static List<ReferenceMigrationAligner.Classification> classifications(
      ReferenceMigrationAligner.MigrationPage page) {
    return page.occurrences().stream()
        .map(ReferenceMigrationAligner.MigrationOccurrence::classification)
        .toList();
  }

  private static ReferenceMigrationAligner.RawPage raw(String markdown) {
    return new ReferenceMigrationAligner.RawPage("vault-ref-page", "page.md", markdown);
  }

  private static ReferenceMigrationAligner.ApprovedDocument approvedRu(String markdown) {
    return ReferenceMigrationAligner.ApprovedDocument.valid(
        "page/ru.md", markdown.getBytes(StandardCharsets.UTF_8));
  }

  private static ReferenceMigrationAligner.ApprovedDocument approvedEn(String markdown) {
    return ReferenceMigrationAligner.ApprovedDocument.valid(
        "page/en.md", markdown.getBytes(StandardCharsets.UTF_8));
  }

  private static VaultReferenceResolver resolver(VaultReferenceCatalog.CatalogEntry... entries) {
    Map<String, VaultReferenceCatalog.CatalogEntry> mapped = new java.util.LinkedHashMap<>();
    for (VaultReferenceCatalog.CatalogEntry entry : entries) {
      mapped.put(entry.pageRef(), entry);
    }
    return new VaultReferenceResolver(new VaultReferenceCatalog(
        VaultReferenceCatalog.SCHEMA_VERSION, mapped));
  }

  private static VaultReferenceCatalog.CatalogEntry publicTarget() {
    return entry("vault-ref-public", "notes/public.md", "Public", "Public", List.of());
  }

  private static VaultReferenceCatalog.CatalogEntry privateTarget() {
    return entry("vault-ref-private", "notes/private.md", "Private", "Private", List.of());
  }

  private static VaultReferenceCatalog.CatalogEntry targetB() {
    return entry("vault-ref-b", "b.md", "B", "B", List.of());
  }

  private static VaultReferenceCatalog.CatalogEntry targetC() {
    return entry("vault-ref-c", "c.md", "C", "C", List.of());
  }

  private static VaultReferenceCatalog.CatalogEntry targetD() {
    return entry("vault-ref-d", "d.md", "D", "D", List.of());
  }

  private static VaultReferenceCatalog.CatalogEntry entry(
      String pageRef,
      String currentPath,
      String stableId,
      String title,
      List<String> aliases) {
    return new VaultReferenceCatalog.CatalogEntry(
        pageRef,
        currentPath,
        stableId,
        title,
        aliases,
        List.of(),
        VaultReferenceCatalog.STATE_ACTIVE);
  }
}
