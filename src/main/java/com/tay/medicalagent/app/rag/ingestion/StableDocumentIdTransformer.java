package com.tay.medicalagent.app.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
/**
 * 为切块后的文档生成稳定 ID，保证重建索引和 manifest 删除行为可重复。
 */
public class StableDocumentIdTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .map(this::withStableId)
                .toList();
    }

    private Document withStableId(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String sourceId = stringValue(metadata.get(MedicalKnowledgeMetadataKeys.SOURCE_ID));
        String section = stringValue(metadata.get(MedicalKnowledgeMetadataKeys.SECTION));
        int chunkIndex = chunkIndex(metadata.get("chunk_index"));
        String stableId = stableId(sourceId + "|" + section + "|" + chunkIndex);

        Document rewritten = document.mutate()
                .id(stableId)
                .build();
        rewritten.setContentFormatter(document.getContentFormatter());
        return rewritten;
    }

    private int chunkIndex(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            }
            catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String stableId(String raw) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("生成知识库文档 ID 失败", ex);
        }
    }
}
