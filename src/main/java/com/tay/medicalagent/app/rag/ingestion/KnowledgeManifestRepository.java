package com.tay.medicalagent.app.rag.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Repository
/**
 * 知识库索引清单仓储。
 * <p>
 * 用于记录最近一次入库生成的 document id 列表，便于重建时先清理旧索引。
 */
public class KnowledgeManifestRepository {

    private final MedicalRagProperties medicalRagProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeManifestRepository(MedicalRagProperties medicalRagProperties) {
        this.medicalRagProperties = medicalRagProperties;
    }

    public List<String> loadIndexedIds() {
        File manifestFile = manifestFile();
        if (!manifestFile.exists() || !manifestFile.isFile()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(manifestFile, new TypeReference<>() {
            });
        }
        catch (IOException ex) {
            throw new IllegalStateException("读取知识库 manifest 失败: " + manifestFile.getAbsolutePath(), ex);
        }
    }

    public void saveIndexedIds(List<String> indexedIds) {
        File manifestFile = manifestFile();
        ensureParentDirectory(manifestFile);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile, indexedIds);
        }
        catch (IOException ex) {
            throw new IllegalStateException("写入知识库 manifest 失败: " + manifestFile.getAbsolutePath(), ex);
        }
    }

    public String manifestLocation() {
        return manifestFile().getPath();
    }

    public boolean hasIndexedIds() {
        return !loadIndexedIds().isEmpty();
    }

    private File manifestFile() {
        return new File(medicalRagProperties.getVectorStore().getManifestFile());
    }

    private void ensureParentDirectory(File file) {
        File parent = file.getParentFile();
        if (parent == null || parent.exists()) {
            return;
        }
        try {
            Files.createDirectories(parent.toPath());
        }
        catch (IOException ex) {
            throw new IllegalStateException("创建 manifest 目录失败: " + parent.getAbsolutePath(), ex);
        }
    }
}
