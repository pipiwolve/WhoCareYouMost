package com.tay.medicalagent.app.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MedicalRagPropertiesBindingTest {

    @Test
    void shouldBindSharedManifestFileAndElasticsearchHybridSettings() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "medical.rag.vector-store.type", "elasticsearch",
                "medical.rag.vector-store.manifest-file", "/tmp/knowledge-manifest.json",
                "medical.rag.retrieval.strategy", "elasticsearch_hybrid",
                "medical.rag.retrieval.elasticsearch-hybrid.vector-top-k", "32",
                "medical.rag.retrieval.elasticsearch-hybrid.lexical-top-k", "24",
                "medical.rag.retrieval.elasticsearch-hybrid.rank-constant", "80",
                "medical.rag.retrieval.elasticsearch-hybrid.title-boost", "3.0"
        )));

        MedicalRagProperties properties = binder.bind("medical.rag", Bindable.of(MedicalRagProperties.class))
                .orElseThrow(() -> new IllegalStateException("medical.rag binding failed"));

        assertEquals("elasticsearch", properties.getVectorStore().getType());
        assertEquals("/tmp/knowledge-manifest.json", properties.getVectorStore().getManifestFile());
        assertEquals("elasticsearch_hybrid", properties.getRetrieval().getStrategy());
        assertEquals(32, properties.getRetrieval().getElasticsearchHybrid().getVectorTopK());
        assertEquals(24, properties.getRetrieval().getElasticsearchHybrid().getLexicalTopK());
        assertEquals(80, properties.getRetrieval().getElasticsearchHybrid().getRankConstant());
        assertEquals(3.0d, properties.getRetrieval().getElasticsearchHybrid().getTitleBoost());
    }

    @Test
    void shouldResolveDefaultStrategyFromVectorStoreType() {
        MedicalRagProperties properties = new MedicalRagProperties();

        assertEquals("elasticsearch", properties.getVectorStore().getType());
        assertEquals("vector", properties.getRetrieval().resolveStrategy("simple"));
        assertEquals("elasticsearch_hybrid", properties.getRetrieval().resolveStrategy("elasticsearch"));
    }
}
