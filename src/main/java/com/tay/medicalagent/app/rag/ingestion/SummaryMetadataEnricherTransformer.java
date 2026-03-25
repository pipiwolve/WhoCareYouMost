package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher.SummaryType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
/**
 * 对官方 SummaryMetadataEnricher 的懒加载包装，避免在上下文启动阶段提前初始化模型。
 */
public class SummaryMetadataEnricherTransformer implements DocumentTransformer {

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final MedicalRagProperties medicalRagProperties;

    public SummaryMetadataEnricherTransformer(
            MedicalAiModelProvider medicalAiModelProvider,
            MedicalRagProperties medicalRagProperties
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        MedicalRagProperties.Ingestion.Summary summary = medicalRagProperties.getIngestion().getSummary();
        if (!summary.isEnabled() || documents.isEmpty()) {
            return documents;
        }

        List<SummaryType> summaryTypes = summary.getTypes().isEmpty()
                ? List.of(SummaryType.CURRENT)
                : List.copyOf(summary.getTypes());

        SummaryMetadataEnricher enricher;
        if (StringUtils.hasText(summary.getTemplate())) {
            enricher = new SummaryMetadataEnricher(
                    medicalAiModelProvider.getChatModel(),
                    summaryTypes,
                    summary.getTemplate(),
                    summary.getMetadataMode()
            );
        }
        else {
            enricher = new SummaryMetadataEnricher(medicalAiModelProvider.getChatModel(), summaryTypes);
        }
        return enricher.apply(documents);
    }
}
