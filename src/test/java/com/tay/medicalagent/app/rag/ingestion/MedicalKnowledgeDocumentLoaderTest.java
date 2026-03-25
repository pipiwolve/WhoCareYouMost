package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalKnowledgeDocumentLoaderTest {

    @Test
    void shouldStripFrontMatterAndNormalizeMetadata() {
        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getIngestion().setResourceLocation("classpath*:rag/ingestion/front-matter-sample.md");

        MedicalKnowledgeDocumentLoader loader = new MedicalKnowledgeDocumentLoader(medicalRagProperties);
        MedicalKnowledgeMetadataNormalizerTransformer normalizer = new MedicalKnowledgeMetadataNormalizerTransformer();

        List<Document> rawDocuments = loader.read();
        assertEquals(2, rawDocuments.size());
        assertTrue(rawDocuments.stream().noneMatch(document -> document.getText().contains("sourceId: kb-front-matter-sample")));
        assertTrue(rawDocuments.stream().noneMatch(document -> document.getText().contains("department: emergency")));

        List<Document> normalizedDocuments = normalizer.transform(rawDocuments);
        assertEquals(2, normalizedDocuments.size());

        Document first = normalizedDocuments.get(0);
        Document second = normalizedDocuments.get(1);

        assertEquals("kb-front-matter-sample", first.getMetadata().get(MedicalKnowledgeMetadataKeys.SOURCE_ID));
        assertEquals("医疗文章总标题", first.getMetadata().get(MedicalKnowledgeMetadataKeys.TITLE));
        assertEquals("第一节", first.getMetadata().get(MedicalKnowledgeMetadataKeys.SECTION));
        assertTrue(first.getMetadata().containsKey(MedicalKnowledgeMetadataKeys.URI));
        assertFalse(first.getMetadata().containsKey("department"));
        assertFalse(first.getMetadata().containsKey(MedicalKnowledgeMetadataKeys.TEMP_ARTICLE_TITLE));

        assertEquals("医疗文章总标题", second.getMetadata().get(MedicalKnowledgeMetadataKeys.TITLE));
        assertEquals("第二节", second.getMetadata().get(MedicalKnowledgeMetadataKeys.SECTION));
    }
}
