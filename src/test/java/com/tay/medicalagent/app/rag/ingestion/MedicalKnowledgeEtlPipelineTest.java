package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MedicalKnowledgeEtlPipelineTest {

    @Test
    void shouldSplitDocumentsGenerateStableIdsAndEnrichChunks() {
        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getIngestion().getTokenSplitter().setChunkSize(20);
        medicalRagProperties.getIngestion().getTokenSplitter().setMinChunkSizeChars(20);
        medicalRagProperties.getIngestion().getTokenSplitter().setMinChunkLengthToEmbed(5);
        medicalRagProperties.getIngestion().getTokenSplitter().setMaxNumChunks(20);

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(medicalRagProperties.getIngestion().getTokenSplitter().getChunkSize())
                .withMinChunkSizeChars(medicalRagProperties.getIngestion().getTokenSplitter().getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(medicalRagProperties.getIngestion().getTokenSplitter().getMinChunkLengthToEmbed())
                .withMaxNumChunks(medicalRagProperties.getIngestion().getTokenSplitter().getMaxNumChunks())
                .withKeepSeparator(medicalRagProperties.getIngestion().getTokenSplitter().isKeepSeparator())
                .build();

        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String responseText = prompt.getContents().contains("Keywords:")
                    ? "chest pain, sweating, nausea, emergency, triage"
                    : "Chest pain emergency summary";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        });

        SummaryMetadataEnricherTransformer summaryTransformer =
                new SummaryMetadataEnricherTransformer(medicalAiModelProvider, medicalRagProperties);
        KeywordMetadataEnricherTransformer keywordTransformer =
                new KeywordMetadataEnricherTransformer(medicalAiModelProvider, medicalRagProperties);
        StableDocumentIdTransformer stableDocumentIdTransformer = new StableDocumentIdTransformer();

        Document sourceDocument = Document.builder()
                .text("Chest pain with sweating and nausea requires emergency evaluation. ".repeat(20))
                .metadata(Map.of(
                        MedicalKnowledgeMetadataKeys.SOURCE_ID, "kb-chest-pain",
                        MedicalKnowledgeMetadataKeys.TITLE, "Chest Pain Article",
                        MedicalKnowledgeMetadataKeys.SECTION, "Emergency Signs"
                ))
                .build();

        List<Document> splitDocuments = splitter.transform(List.of(sourceDocument));
        assertTrue(splitDocuments.size() > 1);

        List<Document> firstPass = stableDocumentIdTransformer.transform(splitDocuments);
        List<Document> secondPass = stableDocumentIdTransformer.transform(splitDocuments);
        assertEquals(
                firstPass.stream().map(Document::getId).toList(),
                secondPass.stream().map(Document::getId).toList()
        );

        List<Document> summarizedDocuments = summaryTransformer.transform(firstPass);
        List<Document> enrichedDocuments = keywordTransformer.transform(summarizedDocuments);

        assertTrue(enrichedDocuments.stream().allMatch(doc -> doc.getId().matches("[0-9a-f]{32}")));
        assertTrue(enrichedDocuments.stream().allMatch(doc -> doc.getMetadata().containsKey("section_summary")));
        assertTrue(enrichedDocuments.stream().allMatch(doc -> doc.getMetadata().containsKey("excerpt_keywords")));
        verify(chatModel, times(splitDocuments.size() * 2)).call(any(Prompt.class));
    }

    @Test
    void shouldSkipSummaryModelCallWhenDisabled() {
        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getIngestion().getSummary().setEnabled(false);

        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        SummaryMetadataEnricherTransformer transformer =
                new SummaryMetadataEnricherTransformer(medicalAiModelProvider, medicalRagProperties);

        List<Document> documents = transformer.transform(List.of(new Document("summary should not run", Map.of())));

        assertEquals(1, documents.size());
        assertFalse(documents.get(0).getMetadata().containsKey("section_summary"));
        verifyNoInteractions(medicalAiModelProvider);
    }

    @Test
    void shouldSkipKeywordModelCallWhenDisabled() {
        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getIngestion().getKeyword().setEnabled(false);

        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        KeywordMetadataEnricherTransformer transformer =
                new KeywordMetadataEnricherTransformer(medicalAiModelProvider, medicalRagProperties);

        List<Document> documents = transformer.transform(List.of(new Document("keyword should not run", Map.of())));

        assertEquals(1, documents.size());
        assertFalse(documents.get(0).getMetadata().containsKey("excerpt_keywords"));
        verifyNoInteractions(medicalAiModelProvider);
    }
}
