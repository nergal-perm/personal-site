package dev.eugene.publicationexporter.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FieldContractTest {

    @Test
    void nonBlankStructuredListBuildsTheRequiredMembersShape() {
        FieldContract contract = FieldContract.nonBlankStructuredList("relations", List.of("name", "relation"));

        assertEquals("relations", contract.name());
        assertEquals(FieldContract.Type.STRUCTURED_LIST, contract.type());
        assertEquals(List.of("name", "relation"), contract.structuredMembers());
    }

    @Test
    void equalStructuredListContractsMatchOnNameTypeAndMembersOnly() {
        FieldContract first = FieldContract.nonBlankStructuredList("relations", List.of("name", "relation"));
        FieldContract second = FieldContract.nonBlankStructuredList("relations", List.of("name", "relation"));
        FieldContract differentMembers = FieldContract.nonBlankStructuredList("relations", List.of("name"));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, differentMembers);
        assertNotEquals(first.hashCode(), differentMembers.hashCode());
    }
}
