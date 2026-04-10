package com.tay.medicalagent.app.rag.config;

import com.tay.medicalagent.app.config.MedicalRuntimeProperties;
import com.tay.medicalagent.app.rag.vectorstore.ElasticsearchBackedVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.elasticsearch.client.RestClient;

import java.io.File;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

@Configuration
@EnableConfigurationProperties({MedicalRagProperties.class, MedicalRagElasticsearchProperties.class})
/**
 * RAG 相关 Bean 配置。
 * <p>
 * 当前装配查询/文档双路 EmbeddingModel，并根据配置切换 SimpleVectorStore 或 ElasticsearchVectorStore。
 */
public class MedicalRagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MedicalRagConfiguration.class);

    private final MedicalRuntimeProperties medicalRuntimeProperties;

    public MedicalRagConfiguration(MedicalRuntimeProperties medicalRuntimeProperties) {
        this.medicalRuntimeProperties = medicalRuntimeProperties;
    }

    @Bean
    @ConditionalOnProperty(prefix = "medical.rag.vector-store", name = "type", havingValue = "simple")
    public VectorStore simpleMedicalVectorStore(
            @Qualifier("medicalQueryAwareEmbeddingModel") EmbeddingModel medicalEmbeddingModel,
            @Qualifier("testEmbeddingModel") ObjectProvider<EmbeddingModel> testEmbeddingModelProvider,
            MedicalRagProperties medicalRagProperties
    ) {
        EmbeddingModel embeddingModel = testEmbeddingModelProvider.getIfAvailable(() -> medicalEmbeddingModel);
        return buildSimpleVectorStore(embeddingModel, medicalRagProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "medical.rag.vector-store", name = "type", havingValue = "elasticsearch", matchIfMissing = true)
    public VectorStore elasticsearchMedicalVectorStore(
            RestClient restClient,
            @Qualifier("medicalQueryAwareEmbeddingModel") EmbeddingModel medicalEmbeddingModel,
            @Qualifier("testEmbeddingModel") ObjectProvider<EmbeddingModel> testEmbeddingModelProvider,
            MedicalRagProperties medicalRagProperties,
            MedicalRagElasticsearchProperties elasticsearchProperties
    ) {
        EmbeddingModel embeddingModel = testEmbeddingModelProvider.getIfAvailable(() -> medicalEmbeddingModel);
        try {
            return buildElasticsearchVectorStore(restClient, embeddingModel, elasticsearchProperties);
        }
        catch (RuntimeException ex) {
            if (!medicalRagProperties.getVectorStore().getElasticsearch().isFallbackToSimpleOnStartupFailure()
                    || !isConnectionFailure(ex)
                    || !medicalRuntimeProperties.isLocalLike()) {
                throw ex;
            }

            log.warn(
                    "Elasticsearch vector store startup failed, fallback to SimpleVectorStore. environment={}, indexName={}, storeFile={}, reason={}",
                    medicalRuntimeProperties.resolvedEnvironment().value(),
                    elasticsearchProperties.getIndexName(),
                    medicalRagProperties.getVectorStore().getSimple().getStoreFile(),
                    summarizeFailure(ex)
            );
            return buildSimpleVectorStore(embeddingModel, medicalRagProperties);
        }
    }

    VectorStore buildSimpleVectorStore(EmbeddingModel embeddingModel, MedicalRagProperties medicalRagProperties) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = new File(medicalRagProperties.getVectorStore().getSimple().getStoreFile());
        if (storeFile.exists() && storeFile.isFile()) {
            vectorStore.load(storeFile);
        }
        return vectorStore;
    }

    VectorStore buildElasticsearchVectorStore(
                RestClient restClient,
            EmbeddingModel embeddingModel,
            MedicalRagElasticsearchProperties elasticsearchProperties
    ) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(elasticsearchProperties.getIndexName());
        options.setDimensions(elasticsearchProperties.getDimensions());
        options.setSimilarity(elasticsearchProperties.getSimilarity());
        options.setEmbeddingFieldName(elasticsearchProperties.getEmbeddingFieldName());

        ElasticsearchVectorStore vectorStore = ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(elasticsearchProperties.isInitializeSchema())
                .build();
        vectorStore.afterPropertiesSet();
        return new ElasticsearchBackedVectorStore(vectorStore);
    }

    private boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String summarizeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return current.getClass().getSimpleName() + ": " + message;
    }

    @Bean
    @ConditionalOnMissingBean(TokenTextSplitter.class)
    public TokenTextSplitter medicalKnowledgeTokenTextSplitter(MedicalRagProperties medicalRagProperties) {
        MedicalRagProperties.Ingestion.TokenSplitter tokenSplitter = medicalRagProperties.getIngestion().getTokenSplitter();
        return TokenTextSplitter.builder()
                .withChunkSize(tokenSplitter.getChunkSize())
                .withMinChunkSizeChars(tokenSplitter.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(tokenSplitter.getMinChunkLengthToEmbed())
                .withMaxNumChunks(tokenSplitter.getMaxNumChunks())
                .withKeepSeparator(tokenSplitter.isKeepSeparator())
                .build();
    }
}
