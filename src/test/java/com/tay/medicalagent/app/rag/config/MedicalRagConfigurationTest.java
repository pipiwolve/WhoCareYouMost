package com.tay.medicalagent.app.rag.config;

import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MedicalRagConfigurationTest {

    @Test
    void shouldFallbackToSimpleVectorStoreWhenElasticsearchStartupConnectionFails() {
        MedicalRagConfiguration configuration = new MedicalRagConfiguration() {
            @Override
            VectorStore buildElasticsearchVectorStore(
                    RestClient restClient,
                    EmbeddingModel embeddingModel,
                    MedicalRagElasticsearchProperties elasticsearchProperties
            ) {
                throw new IllegalStateException(new ConnectException("Connection refused"));
            }
        };

        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getVectorStore().getElasticsearch().setFallbackToSimpleOnStartupFailure(true);
        MedicalRagElasticsearchProperties elasticsearchProperties = new MedicalRagElasticsearchProperties();

        VectorStore vectorStore = configuration.elasticsearchMedicalVectorStore(
                mock(RestClient.class),
                mock(EmbeddingModel.class),
                medicalRagProperties,
                elasticsearchProperties
        );

        assertInstanceOf(SimpleVectorStore.class, vectorStore);
    }

    @Test
    void shouldFailFastWhenElasticsearchStartupFallbackDisabled() {
        MedicalRagConfiguration configuration = new MedicalRagConfiguration() {
            @Override
            VectorStore buildElasticsearchVectorStore(
                    RestClient restClient,
                    EmbeddingModel embeddingModel,
                    MedicalRagElasticsearchProperties elasticsearchProperties
            ) {
                throw new IllegalStateException(new ConnectException("Connection refused"));
            }
        };

        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getVectorStore().getElasticsearch().setFallbackToSimpleOnStartupFailure(false);
        MedicalRagElasticsearchProperties elasticsearchProperties = new MedicalRagElasticsearchProperties();

        assertThrows(IllegalStateException.class, () -> configuration.elasticsearchMedicalVectorStore(
                mock(RestClient.class),
                mock(EmbeddingModel.class),
                medicalRagProperties,
                elasticsearchProperties
        ));
    }
}
