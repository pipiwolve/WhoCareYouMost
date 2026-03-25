package com.tay.medicalagent.app.rag.config;

import com.tay.medicalagent.app.rag.embedding.MedicalQueryAwareEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.elasticsearch.client.RestClient;

import java.io.File;

@Configuration
@EnableConfigurationProperties({MedicalRagProperties.class, MedicalRagElasticsearchProperties.class})
/**
 * RAG 相关 Bean 配置。
 * <p>
 * 当前装配查询/文档双路 EmbeddingModel，并根据配置切换 SimpleVectorStore 或 ElasticsearchVectorStore。
 */
public class MedicalRagConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel medicalRagEmbeddingModel(MedicalQueryAwareEmbeddingModel medicalQueryAwareEmbeddingModel) {
        return medicalQueryAwareEmbeddingModel;
    }

    @Bean
    @ConditionalOnProperty(prefix = "medical.rag.vector-store", name = "type", havingValue = "simple")
    public VectorStore simpleMedicalVectorStore(EmbeddingModel embeddingModel, MedicalRagProperties medicalRagProperties) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = new File(medicalRagProperties.getVectorStore().getSimple().getStoreFile());
        if (storeFile.exists() && storeFile.isFile()) {
            vectorStore.load(storeFile);
        }
        return vectorStore;
    }

    @Bean
    @ConditionalOnProperty(prefix = "medical.rag.vector-store", name = "type", havingValue = "elasticsearch", matchIfMissing = true)
    public VectorStore elasticsearchMedicalVectorStore(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            MedicalRagElasticsearchProperties elasticsearchProperties
    ) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(elasticsearchProperties.getIndexName());
        options.setDimensions(elasticsearchProperties.getDimensions());
        options.setSimilarity(elasticsearchProperties.getSimilarity());
        options.setEmbeddingFieldName(elasticsearchProperties.getEmbeddingFieldName());

        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(elasticsearchProperties.isInitializeSchema())
                .build();
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
