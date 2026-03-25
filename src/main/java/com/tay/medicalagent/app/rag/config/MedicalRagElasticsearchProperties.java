package com.tay.medicalagent.app.rag.config;

import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.vectorstore.elasticsearch")
public class MedicalRagElasticsearchProperties {

    private boolean initializeSchema = true;

    private String indexName = "medical-rag";

    private int dimensions = 1536;

    private SimilarityFunction similarity = SimilarityFunction.cosine;

    private String embeddingFieldName = "embedding";

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public SimilarityFunction getSimilarity() {
        return similarity;
    }

    public void setSimilarity(SimilarityFunction similarity) {
        this.similarity = similarity;
    }

    public String getEmbeddingFieldName() {
        return embeddingFieldName;
    }

    public void setEmbeddingFieldName(String embeddingFieldName) {
        this.embeddingFieldName = embeddingFieldName;
    }
}
