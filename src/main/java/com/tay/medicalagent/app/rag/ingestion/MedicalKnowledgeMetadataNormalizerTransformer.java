package com.tay.medicalagent.app.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
/**
 * 规范化官方 Reader 输出的文档元数据，仅保留 RAG 检索依赖的最小字段集合。
 */
public class MedicalKnowledgeMetadataNormalizerTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .map(this::normalize)
                .toList();
    }

    private Document normalize(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : document.getMetadata();
        String filename = value(metadata.get(MedicalKnowledgeMetadataKeys.TEMP_RESOURCE_FILENAME));
        String articleTitle = firstNonBlank(
                value(metadata.get(MedicalKnowledgeMetadataKeys.TEMP_ARTICLE_TITLE)),
                removeExtension(filename)
        );
        String section = firstNonBlank(
                value(metadata.get(MedicalKnowledgeMetadataKeys.TITLE)),
                articleTitle
        );

        Map<String, Object> normalizedMetadata = new LinkedHashMap<>();
        normalizedMetadata.put(MedicalKnowledgeMetadataKeys.SOURCE_ID, firstNonBlank(
                value(metadata.get(MedicalKnowledgeMetadataKeys.SOURCE_ID)),
                "kb-" + sanitizeFileName(filename)
        ));
        normalizedMetadata.put(MedicalKnowledgeMetadataKeys.TITLE, articleTitle);
        normalizedMetadata.put(MedicalKnowledgeMetadataKeys.SECTION, section);

        String uri = value(metadata.get(MedicalKnowledgeMetadataKeys.URI));
        if (!uri.isBlank()) {
            normalizedMetadata.put(MedicalKnowledgeMetadataKeys.URI, uri);
        }

        Document normalized = document.mutate()
                .metadata(normalizedMetadata)
                .build();
        normalized.setContentFormatter(document.getContentFormatter());
        return normalized;
    }

    private String sanitizeFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        return removeExtension(filename).replaceAll("[^a-zA-Z0-9\\p{IsHan}]+", "-").toLowerCase();
    }

    private String removeExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
