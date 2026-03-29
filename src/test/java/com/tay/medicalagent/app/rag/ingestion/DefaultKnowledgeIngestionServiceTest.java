package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagElasticsearchProperties;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultKnowledgeIngestionServiceTest {

    @Test
    void shouldWriteDocumentsInBatchesOfTwentyFive() {
        VectorStore vectorStore = mock(VectorStore.class);
        MedicalKnowledgeDocumentLoader documentLoader = mock(MedicalKnowledgeDocumentLoader.class);
        MedicalKnowledgeMetadataNormalizerTransformer metadataNormalizerTransformer = mock(MedicalKnowledgeMetadataNormalizerTransformer.class);
        TokenTextSplitter tokenTextSplitter = mock(TokenTextSplitter.class);
        StableDocumentIdTransformer stableDocumentIdTransformer = mock(StableDocumentIdTransformer.class);
        SummaryMetadataEnricherTransformer summaryMetadataEnricherTransformer = mock(SummaryMetadataEnricherTransformer.class);
        KeywordMetadataEnricherTransformer keywordMetadataEnricherTransformer = mock(KeywordMetadataEnricherTransformer.class);
        KnowledgeManifestRepository knowledgeManifestRepository = mock(KnowledgeManifestRepository.class);

        List<Document> documents = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> Document.builder()
                        .id("doc-" + index)
                        .text("text-" + index)
                        .metadata(Map.of(MedicalKnowledgeMetadataKeys.SOURCE_ID, "source-" + index))
                        .build())
                .toList();

        when(knowledgeManifestRepository.loadIndexedIds()).thenReturn(List.of());
        when(documentLoader.read()).thenReturn(documents);
        when(documentLoader.sourceFileCount()).thenReturn(3);
        when(metadataNormalizerTransformer.transform(documents)).thenReturn(documents);
        when(tokenTextSplitter.transform(documents)).thenReturn(documents);
        when(stableDocumentIdTransformer.transform(documents)).thenReturn(documents);
        when(summaryMetadataEnricherTransformer.transform(documents)).thenReturn(documents);
        when(keywordMetadataEnricherTransformer.transform(documents)).thenReturn(documents);
        when(knowledgeManifestRepository.manifestLocation()).thenReturn("manifest.json");

        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getVectorStore().setType("elasticsearch");

        DefaultKnowledgeIngestionService service = new DefaultKnowledgeIngestionService(
                vectorStore,
                documentLoader,
                metadataNormalizerTransformer,
                tokenTextSplitter,
                stableDocumentIdTransformer,
                summaryMetadataEnricherTransformer,
                keywordMetadataEnricherTransformer,
                knowledgeManifestRepository,
                medicalRagProperties,
                new MedicalRagElasticsearchProperties()
        );

        service.reindexKnowledgeBase();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).write(batchCaptor.capture());
        verify(knowledgeManifestRepository).saveIndexedIds(documents.stream().map(Document::getId).toList());

        List<List<Document>> batches = batchCaptor.getAllValues();
        assertEquals(2, batches.size());
        assertEquals(25, batches.get(0).size());
        assertEquals(5, batches.get(1).size());
    }
}
