package com.tay.medicalagent.app.rag.embedding;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
/**
 * 面向医疗 RAG 的 EmbeddingModel 适配器。
 * <p>
 * 该实现将 DashScope 的 query/document 两种 text-type 显式区分开，
 * 避免检索查询与知识文档共用同一种向量化策略。
 */
public class MedicalQueryAwareEmbeddingModel implements EmbeddingModel {

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final MedicalRagProperties medicalRagProperties;

    private volatile DashScopeEmbeddingModel documentEmbeddingModel;
    private volatile DashScopeEmbeddingModel queryEmbeddingModel;

    public MedicalQueryAwareEmbeddingModel(
            MedicalAiModelProvider medicalAiModelProvider,
            MedicalRagProperties medicalRagProperties
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        String textType = resolveTextType(request);
        return selectModel(textType).call(request);
    }

    @Override
    public float[] embed(Document document) {
        return getDocumentEmbeddingModel().embed(document);
    }

    @Override
    public float[] embed(String text) {
        return getQueryEmbeddingModel().embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return getQueryEmbeddingModel().embed(texts);
    }

    @Override
    public int dimensions() {
        return medicalRagProperties.getEmbedding().getDimensions();
    }

    private DashScopeEmbeddingModel selectModel(String textType) {
        if (medicalRagProperties.getEmbedding().getQueryTextType().equalsIgnoreCase(textType)) {
            return getQueryEmbeddingModel();
        }
        return getDocumentEmbeddingModel();
    }

    private String resolveTextType(EmbeddingRequest request) {
        if (request != null && request.getOptions() instanceof DashScopeEmbeddingOptions options) {
            String textType = options.getTextType();
            if (textType != null && !textType.isBlank()) {
                return textType;
            }
        }
        return medicalRagProperties.getEmbedding().getDocumentTextType();
    }

    private DashScopeEmbeddingModel getDocumentEmbeddingModel() {
        if (documentEmbeddingModel == null) {
            synchronized (this) {
                if (documentEmbeddingModel == null) {
                    documentEmbeddingModel = new DashScopeEmbeddingModel(
                            medicalAiModelProvider.getDashScopeApi(),
                            MetadataMode.EMBED,
                            DashScopeEmbeddingOptions.builder()
                                    .model(medicalRagProperties.getEmbedding().getModel())
                                    .dimensions(medicalRagProperties.getEmbedding().getDimensions())
                                    .textType(medicalRagProperties.getEmbedding().getDocumentTextType())
                                    .build()
                    );
                }
            }
        }
        return documentEmbeddingModel;
    }

    private DashScopeEmbeddingModel getQueryEmbeddingModel() {
        if (queryEmbeddingModel == null) {
            synchronized (this) {
                if (queryEmbeddingModel == null) {
                    queryEmbeddingModel = new DashScopeEmbeddingModel(
                            medicalAiModelProvider.getDashScopeApi(),
                            MetadataMode.EMBED,
                            DashScopeEmbeddingOptions.builder()
                                    .model(medicalRagProperties.getEmbedding().getModel())
                                    .dimensions(medicalRagProperties.getEmbedding().getDimensions())
                                    .textType(medicalRagProperties.getEmbedding().getQueryTextType())
                                    .build()
                    );
                }
            }
        }
        return queryEmbeddingModel;
    }
}
