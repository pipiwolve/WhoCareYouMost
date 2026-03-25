package com.tay.medicalagent.app.rag.retrieval;

import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    static List<Document> fuse(List<Document> vectorDocuments, List<Document> lexicalDocuments, int topK, int rankConstant) {
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Document> originals = new LinkedHashMap<>();

        apply(vectorDocuments, fusedScores, originals, rankConstant);
        apply(lexicalDocuments, fusedScores, originals, rankConstant);

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(Math.max(1, topK))
                .map(entry -> withScore(originals.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static void apply(
            List<Document> documents,
            Map<String, Double> fusedScores,
            Map<String, Document> originals,
            int rankConstant
    ) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String key = stableKey(document);
            originals.putIfAbsent(key, document);
            fusedScores.merge(key, 1.0d / (rankConstant + index + 1), Double::sum);
        }
    }

    private static Document withScore(Document document, double score) {
        Document rewritten = document.mutate()
                .score(score)
                .build();
        rewritten.setContentFormatter(document.getContentFormatter());
        return rewritten;
    }

    private static String stableKey(Document document) {
        String id = document.getId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        String text = document.getText();
        return text == null ? "unknown" : text;
    }
}
