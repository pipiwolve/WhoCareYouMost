package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.RagContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMedicalKnowledgeRetrieverTest {

    @Test
    void shouldDeduplicatePassagesForContextAndSources() {
        VectorStore vectorStore = mock(VectorStore.class);
        MedicalQueryBuilder medicalQueryBuilder = mock(MedicalQueryBuilder.class);
        ElasticsearchHybridSearchClient elasticsearchHybridSearchClient = mock(ElasticsearchHybridSearchClient.class);
        MedicalRagProperties properties = new MedicalRagProperties();
        properties.getVectorStore().setType("simple");

        when(medicalQueryBuilder.normalizeQuery(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Document duplicatePassageA = mock(Document.class);
        when(duplicatePassageA.getId()).thenReturn("doc-1");
        when(duplicatePassageA.getText()).thenReturn("第一段胸痛分诊内容");
        when(duplicatePassageA.getScore()).thenReturn(0.91);
        when(duplicatePassageA.getMetadata()).thenReturn(Map.of(
                "sourceId", "kb-dizziness-syncope-triage",
                "title", "头晕与晕厥分诊",
                "section", "体位性因素",
                "uri", "/tmp/a.md"
        ));

        Document duplicatePassageB = mock(Document.class);
        when(duplicatePassageB.getId()).thenReturn("doc-2");
        when(duplicatePassageB.getText()).thenReturn("第二段重复的体位性因素内容");
        when(duplicatePassageB.getScore()).thenReturn(0.88);
        when(duplicatePassageB.getMetadata()).thenReturn(Map.of(
                "sourceId", "kb-dizziness-syncope-triage",
                "title", "头晕与晕厥分诊",
                "section", "体位性因素",
                "uri", "/tmp/b.md"
        ));

        Document distinctPassage = mock(Document.class);
        when(distinctPassage.getId()).thenReturn("doc-3");
        when(distinctPassage.getText()).thenReturn("脱水相关建议");
        when(distinctPassage.getScore()).thenReturn(0.72);
        when(distinctPassage.getMetadata()).thenReturn(Map.of(
                "sourceId", "kb-dehydration-triage",
                "title", "脱水分诊",
                "section", "观察与补液",
                "uri", "/tmp/c.md"
        ));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(duplicatePassageA, duplicatePassageB, distinctPassage));

        DefaultMedicalKnowledgeRetriever retriever = new DefaultMedicalKnowledgeRetriever(
                vectorStore,
                medicalQueryBuilder,
                properties,
                elasticsearchHybridSearchClient
        );

        RagContext ragContext = retriever.retrieve("站立时头晕明显");

        assertEquals(2, ragContext.passages().size());
        assertEquals(2, ragContext.sources().size());
        assertTrue(ragContext.contextText().contains("kb-dizziness-syncope-triage"));
        assertEquals(1, countOccurrences(ragContext.contextText(), "kb-dizziness-syncope-triage"));
    }

    private int countOccurrences(String text, String token) {
        return text.split(token, -1).length - 1;
    }
}
