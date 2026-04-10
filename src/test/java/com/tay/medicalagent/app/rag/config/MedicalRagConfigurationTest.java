package com.tay.medicalagent.app.rag.config;

import com.tay.medicalagent.app.config.MedicalRuntimeProperties;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.net.ConnectException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MedicalRagConfigurationTest {

    @Test
    void shouldFallbackToSimpleVectorStoreWhenElasticsearchStartupConnectionFails() {
        MedicalRuntimeProperties runtimeProperties = new MedicalRuntimeProperties();
        runtimeProperties.setEnvironment("local");
        MedicalRagConfiguration configuration = new MedicalRagConfiguration(runtimeProperties) {
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
        EmbeddingModel testEmbeddingModel = mock(EmbeddingModel.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(
            Map.of("testEmbeddingModel", testEmbeddingModel)
        );

        VectorStore vectorStore = configuration.elasticsearchMedicalVectorStore(
                mock(RestClient.class),
                mock(EmbeddingModel.class),
            beanFactory.getBeanProvider(EmbeddingModel.class),
                medicalRagProperties,
                elasticsearchProperties
        );

        assertInstanceOf(SimpleVectorStore.class, vectorStore);
    }

    @Test
    void shouldFailFastWhenElasticsearchStartupFallbackDisabled() {
        MedicalRuntimeProperties runtimeProperties = new MedicalRuntimeProperties();
        runtimeProperties.setEnvironment("local");
        MedicalRagConfiguration configuration = new MedicalRagConfiguration(runtimeProperties) {
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
        EmbeddingModel testEmbeddingModel = mock(EmbeddingModel.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(
            Map.of("testEmbeddingModel", testEmbeddingModel)
        );

        assertThrows(IllegalStateException.class, () -> configuration.elasticsearchMedicalVectorStore(
                mock(RestClient.class),
                mock(EmbeddingModel.class),
            beanFactory.getBeanProvider(EmbeddingModel.class),
                medicalRagProperties,
                elasticsearchProperties
        ));
    }

    @Test
    void shouldFailFastOutsideLocalEnvironmentEvenWhenFallbackEnabled() {
        MedicalRuntimeProperties runtimeProperties = new MedicalRuntimeProperties();
        runtimeProperties.setEnvironment("prod");
        MedicalRagConfiguration configuration = new MedicalRagConfiguration(runtimeProperties) {
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
        EmbeddingModel testEmbeddingModel = mock(EmbeddingModel.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(
                Map.of("testEmbeddingModel", testEmbeddingModel)
        );

        assertThrows(IllegalStateException.class, () -> configuration.elasticsearchMedicalVectorStore(
                mock(RestClient.class),
                mock(EmbeddingModel.class),
                beanFactory.getBeanProvider(EmbeddingModel.class),
                medicalRagProperties,
                elasticsearchProperties
        ));
    }
}
