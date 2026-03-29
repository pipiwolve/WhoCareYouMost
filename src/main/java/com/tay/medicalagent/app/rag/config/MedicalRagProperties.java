package com.tay.medicalagent.app.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "medical.rag")
/**
 * RAG 模块配置属性。
 * <p>
 * 对应 {@code application.yml} 中的 {@code medical.rag.*} 配置，用于统一管理
 * 知识库入库、向量存储、检索和离线评估参数。
 */
public class MedicalRagProperties {

    private boolean enabled = true;

    private boolean bootstrapOnStartup;

    private String contextMetadataKey = "rag_context";

    private String appliedMetadataKey = "rag_applied";

    private final Embedding embedding = new Embedding();

    private final VectorStore vectorStore = new VectorStore();

    private final Ingestion ingestion = new Ingestion();

    private final Retrieval retrieval = new Retrieval();

    private final Evaluation evaluation = new Evaluation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBootstrapOnStartup() {
        return bootstrapOnStartup;
    }

    public void setBootstrapOnStartup(boolean bootstrapOnStartup) {
        this.bootstrapOnStartup = bootstrapOnStartup;
    }

    public String getContextMetadataKey() {
        return contextMetadataKey;
    }

    public void setContextMetadataKey(String contextMetadataKey) {
        this.contextMetadataKey = contextMetadataKey;
    }

    public String getAppliedMetadataKey() {
        return appliedMetadataKey;
    }

    public void setAppliedMetadataKey(String appliedMetadataKey) {
        this.appliedMetadataKey = appliedMetadataKey;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public static class Embedding {

        private String model = "text-embedding-v2";

        private int dimensions = 1536;

        private String documentTextType = "document";

        private String queryTextType = "query";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public String getDocumentTextType() {
            return documentTextType;
        }

        public void setDocumentTextType(String documentTextType) {
            this.documentTextType = documentTextType;
        }

        public String getQueryTextType() {
            return queryTextType;
        }

        public void setQueryTextType(String queryTextType) {
            this.queryTextType = queryTextType;
        }
    }

    public static class VectorStore {

        private String type = "elasticsearch";

        private String manifestFile = "data/medical-rag/knowledge-manifest.json";

        private final Simple simple = new Simple();

        private final Elasticsearch elasticsearch = new Elasticsearch();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getManifestFile() {
            return manifestFile;
        }

        public void setManifestFile(String manifestFile) {
            this.manifestFile = manifestFile;
        }

        public Simple getSimple() {
            return simple;
        }

        public Elasticsearch getElasticsearch() {
            return elasticsearch;
        }

        public static class Simple {

            private String storeFile = "data/medical-rag/simple-vector-store.json";

            public String getStoreFile() {
                return storeFile;
            }

            public void setStoreFile(String storeFile) {
                this.storeFile = storeFile;
            }
        }

        public static class Elasticsearch {

            private boolean fallbackToSimpleOnStartupFailure;

            public boolean isFallbackToSimpleOnStartupFailure() {
                return fallbackToSimpleOnStartupFailure;
            }

            public void setFallbackToSimpleOnStartupFailure(boolean fallbackToSimpleOnStartupFailure) {
                this.fallbackToSimpleOnStartupFailure = fallbackToSimpleOnStartupFailure;
            }
        }
    }

    public static class Ingestion {

        private String resourceLocation = "classpath*:knowledge-base/medical/**/*";

        private final TokenSplitter tokenSplitter = new TokenSplitter();

        private final Keyword keyword = new Keyword();

        private final Summary summary = new Summary();

        public String getResourceLocation() {
            return resourceLocation;
        }

        public void setResourceLocation(String resourceLocation) {
            this.resourceLocation = resourceLocation;
        }

        public TokenSplitter getTokenSplitter() {
            return tokenSplitter;
        }

        public Keyword getKeyword() {
            return keyword;
        }

        public Summary getSummary() {
            return summary;
        }

        public static class TokenSplitter {

            private int chunkSize = 800;

            private int minChunkSizeChars = 350;

            private int minChunkLengthToEmbed = 5;

            private int maxNumChunks = 10000;

            private boolean keepSeparator = true;

            public int getChunkSize() {
                return chunkSize;
            }

            public void setChunkSize(int chunkSize) {
                this.chunkSize = chunkSize;
            }

            public int getMinChunkSizeChars() {
                return minChunkSizeChars;
            }

            public void setMinChunkSizeChars(int minChunkSizeChars) {
                this.minChunkSizeChars = minChunkSizeChars;
            }

            public int getMinChunkLengthToEmbed() {
                return minChunkLengthToEmbed;
            }

            public void setMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
                this.minChunkLengthToEmbed = minChunkLengthToEmbed;
            }

            public int getMaxNumChunks() {
                return maxNumChunks;
            }

            public void setMaxNumChunks(int maxNumChunks) {
                this.maxNumChunks = maxNumChunks;
            }

            public boolean isKeepSeparator() {
                return keepSeparator;
            }

            public void setKeepSeparator(boolean keepSeparator) {
                this.keepSeparator = keepSeparator;
            }
        }

        public static class Keyword {

            private boolean enabled = true;

            private int count = 5;

            private String template;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getCount() {
                return count;
            }

            public void setCount(int count) {
                this.count = count;
            }

            public String getTemplate() {
                return template;
            }

            public void setTemplate(String template) {
                this.template = template;
            }
        }

        public static class Summary {

            private boolean enabled = true;

            private List<SummaryMetadataEnricher.SummaryType> types =
                    new ArrayList<>(List.of(SummaryMetadataEnricher.SummaryType.CURRENT));

            private MetadataMode metadataMode = MetadataMode.INFERENCE;

            private String template;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public List<SummaryMetadataEnricher.SummaryType> getTypes() {
                return types;
            }

            public void setTypes(List<SummaryMetadataEnricher.SummaryType> types) {
                this.types = types == null ? new ArrayList<>() : new ArrayList<>(types);
            }

            public MetadataMode getMetadataMode() {
                return metadataMode;
            }

            public void setMetadataMode(MetadataMode metadataMode) {
                this.metadataMode = metadataMode;
            }

            public String getTemplate() {
                return template;
            }

            public void setTemplate(String template) {
                this.template = template;
            }
        }
    }

    public static class Retrieval {

        private int topK = 5;

        private double similarityThreshold = 0.35;

        private int maxContextChars = 4000;

        private String strategy;

        private final ElasticsearchHybrid elasticsearchHybrid = new ElasticsearchHybrid();

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public ElasticsearchHybrid getElasticsearchHybrid() {
            return elasticsearchHybrid;
        }

        public String resolveStrategy(String vectorStoreType) {
            if (strategy != null && !strategy.isBlank()) {
                return strategy.trim().toLowerCase();
            }
            return "elasticsearch".equalsIgnoreCase(vectorStoreType) ? "elasticsearch_hybrid" : "vector";
        }

        public static class ElasticsearchHybrid {

            private int vectorTopK = 20;

            private int lexicalTopK = 20;

            private int rankConstant = 60;

            private double titleBoost = 2.5;

            private double sectionBoost = 2.0;

            private double contentBoost = 1.0;

            public int getVectorTopK() {
                return vectorTopK;
            }

            public void setVectorTopK(int vectorTopK) {
                this.vectorTopK = vectorTopK;
            }

            public int getLexicalTopK() {
                return lexicalTopK;
            }

            public void setLexicalTopK(int lexicalTopK) {
                this.lexicalTopK = lexicalTopK;
            }

            public int getRankConstant() {
                return rankConstant;
            }

            public void setRankConstant(int rankConstant) {
                this.rankConstant = rankConstant;
            }

            public double getTitleBoost() {
                return titleBoost;
            }

            public void setTitleBoost(double titleBoost) {
                this.titleBoost = titleBoost;
            }

            public double getSectionBoost() {
                return sectionBoost;
            }

            public void setSectionBoost(double sectionBoost) {
                this.sectionBoost = sectionBoost;
            }

            public double getContentBoost() {
                return contentBoost;
            }

            public void setContentBoost(double contentBoost) {
                this.contentBoost = contentBoost;
            }
        }
    }

    public static class Evaluation {

        private String datasetLocation = "classpath:evaluation/medical-rag-eval.json";

        private int topK = 5;

        public String getDatasetLocation() {
            return datasetLocation;
        }

        public void setDatasetLocation(String datasetLocation) {
            this.datasetLocation = datasetLocation;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }
}
