package com.tay.medicalagent.app.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReciprocalRankFusionTest {

    @Test
    void shouldPrioritizeDocumentsPresentInBothRankings() {
        Document shared = new Document("shared", "shared", Map.of("sourceId", "shared"));
        Document vectorOnly = new Document("vector", "vector", Map.of("sourceId", "vector"));
        Document lexicalOnly = new Document("lexical", "lexical", Map.of("sourceId", "lexical"));

        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(shared, vectorOnly),
                List.of(shared, lexicalOnly),
                3,
                60
        );

        assertEquals(List.of("shared", "vector", "lexical"), fused.stream().map(Document::getId).toList());
    }

    @Test
    void shouldLimitOutputToRequestedTopK() {
        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(
                        new Document("a", "a", Map.of()),
                        new Document("b", "b", Map.of()),
                        new Document("c", "c", Map.of())
                ),
                List.of(),
                2,
                60
        );

        assertEquals(List.of("a", "b"), fused.stream().map(Document::getId).toList());
    }
}
