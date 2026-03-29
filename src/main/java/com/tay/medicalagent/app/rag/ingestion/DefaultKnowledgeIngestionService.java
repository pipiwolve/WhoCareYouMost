package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagElasticsearchProperties;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
/**
 * 默认知识库入库服务。
 * <p>
 * 负责删除旧索引、重新加载原始知识文件、写入向量存储并落盘 manifest。
 */
public class DefaultKnowledgeIngestionService implements KnowledgeIngestionService {

    private static final int MAX_EMBEDDING_BATCH_SIZE = 25;

    private final VectorStore vectorStore;
    private final MedicalKnowledgeDocumentLoader medicalKnowledgeDocumentLoader;
    private final MedicalKnowledgeMetadataNormalizerTransformer metadataNormalizerTransformer;
    private final TokenTextSplitter tokenTextSplitter;
    private final StableDocumentIdTransformer stableDocumentIdTransformer;
    private final SummaryMetadataEnricherTransformer summaryMetadataEnricherTransformer;
    private final KeywordMetadataEnricherTransformer keywordMetadataEnricherTransformer;
    private final KnowledgeManifestRepository knowledgeManifestRepository;
    private final MedicalRagProperties medicalRagProperties;
    private final MedicalRagElasticsearchProperties medicalRagElasticsearchProperties;

    public DefaultKnowledgeIngestionService(
            VectorStore vectorStore,
            MedicalKnowledgeDocumentLoader medicalKnowledgeDocumentLoader,
            MedicalKnowledgeMetadataNormalizerTransformer metadataNormalizerTransformer,
            TokenTextSplitter tokenTextSplitter,
            StableDocumentIdTransformer stableDocumentIdTransformer,
            SummaryMetadataEnricherTransformer summaryMetadataEnricherTransformer,
            KeywordMetadataEnricherTransformer keywordMetadataEnricherTransformer,
            KnowledgeManifestRepository knowledgeManifestRepository,
            MedicalRagProperties medicalRagProperties,
            MedicalRagElasticsearchProperties medicalRagElasticsearchProperties
    ) {
        this.vectorStore = vectorStore;
        this.medicalKnowledgeDocumentLoader = medicalKnowledgeDocumentLoader;
        this.metadataNormalizerTransformer = metadataNormalizerTransformer;
        this.tokenTextSplitter = tokenTextSplitter;
        this.stableDocumentIdTransformer = stableDocumentIdTransformer;
        this.summaryMetadataEnricherTransformer = summaryMetadataEnricherTransformer;
        this.keywordMetadataEnricherTransformer = keywordMetadataEnricherTransformer;
        this.knowledgeManifestRepository = knowledgeManifestRepository;
        this.medicalRagProperties = medicalRagProperties;
        this.medicalRagElasticsearchProperties = medicalRagElasticsearchProperties;
    }

    @Override
    public KnowledgeBaseRefreshResult reindexKnowledgeBase() {
        List<String> existingIds = knowledgeManifestRepository.loadIndexedIds();
        if (!existingIds.isEmpty()) {
            vectorStore.delete(existingIds);
        }

        List<Document> documents = medicalKnowledgeDocumentLoader.read();
        documents = metadataNormalizerTransformer.transform(documents);
        documents = tokenTextSplitter.transform(documents);
        documents = stableDocumentIdTransformer.transform(documents);
        documents = summaryMetadataEnricherTransformer.transform(documents);
        documents = keywordMetadataEnricherTransformer.transform(documents);

        writeDocuments(documents);

        List<String> indexedIds = documents.stream()
                .map(Document::getId)
                .toList();
        knowledgeManifestRepository.saveIndexedIds(indexedIds);
        persistIfSupported();

        Set<String> sourceIds = new LinkedHashSet<>();
        for (Document document : documents) {
            Object sourceId = document.getMetadata().get(MedicalKnowledgeMetadataKeys.SOURCE_ID);
            if (sourceId != null) {
                sourceIds.add(sourceId.toString());
            }
        }

        return new KnowledgeBaseRefreshResult(
                medicalKnowledgeDocumentLoader.sourceFileCount(),
                documents.size(),
                existingIds.size(),
                List.copyOf(sourceIds),
                medicalRagProperties.getVectorStore().getType(),
                knowledgeManifestRepository.manifestLocation(),
                resolveStoreLocation()
        );
    }

    private void persistIfSupported() {
        if (vectorStore instanceof SimpleVectorStore simpleVectorStore) {
            File storeFile = new File(medicalRagProperties.getVectorStore().getSimple().getStoreFile());
            File parent = storeFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            simpleVectorStore.save(storeFile);
        }
    }

    private void writeDocuments(List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }

        // DashScope embeddings accept at most 25 texts per request, so split ETL writes accordingly.
        for (int start = 0; start < documents.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, documents.size());
            vectorStore.write(documents.subList(start, end));
        }
    }

    private String resolveStoreLocation() {
        if ("elasticsearch".equalsIgnoreCase(medicalRagProperties.getVectorStore().getType())) {
            return medicalRagElasticsearchProperties.getIndexName();
        }
        return medicalRagProperties.getVectorStore().getSimple().getStoreFile();
    }
}
