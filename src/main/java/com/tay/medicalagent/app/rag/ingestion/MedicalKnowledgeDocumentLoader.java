package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
/**
 * 医疗知识文档读取器。
 * <p>
 * 使用官方 Reader 读取 Markdown/TXT 资源，并在读取前对 Markdown 的 front matter 做轻量预处理。
 */
public class MedicalKnowledgeDocumentLoader implements DocumentReader {

    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
    private final MedicalRagProperties medicalRagProperties;

    public MedicalKnowledgeDocumentLoader(MedicalRagProperties medicalRagProperties) {
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public List<Document> get() {
        try {
            List<Resource> resources = resolveSupportedResources();
            List<Document> documents = new ArrayList<>();
            for (Resource resource : resources) {
                documents.addAll(readResource(resource));
            }
            return List.copyOf(documents);
        }
        catch (IOException ex) {
            throw new IllegalStateException("加载医疗知识库资源失败", ex);
        }
    }

    public int sourceFileCount() {
        try {
            return resolveSupportedResources().size();
        }
        catch (IOException ex) {
            throw new IllegalStateException("统计医疗知识库资源失败", ex);
        }
    }

    private List<Resource> resolveSupportedResources() throws IOException {
        Resource[] resources = resourcePatternResolver.getResources(medicalRagProperties.getIngestion().getResourceLocation());
        return List.of(resources).stream()
                .filter(this::isSupportedResourceUnchecked)
                .sorted(Comparator.comparing(this::resourceDescription))
                .toList();
    }

    private boolean isSupportedResource(Resource resource) throws IOException {
        if (resource == null || !resource.exists() || !resource.isReadable() || resource.getFilename() == null) {
            return false;
        }
        String filename = resource.getFilename().toLowerCase();
        return filename.endsWith(".md") || filename.endsWith(".txt");
    }

    private boolean isSupportedResourceUnchecked(Resource resource) {
        try {
            return isSupportedResource(resource);
        }
        catch (IOException ex) {
            return false;
        }
    }

    private List<Document> readResource(Resource resource) {
        if (resource.getFilename() != null && resource.getFilename().toLowerCase().endsWith(".md")) {
            return readMarkdown(resource);
        }
        return readText(resource);
    }

    private List<Document> readMarkdown(Resource resource) {
        String content = readResourceContent(resource);
        FrontMatterParseResult parseResult = parseFrontMatter(content);
        Map<String, Object> additionalMetadata = new LinkedHashMap<>();
        additionalMetadata.put(MedicalKnowledgeMetadataKeys.SOURCE_ID,
                stringValue(parseResult.metadata().getOrDefault(MedicalKnowledgeMetadataKeys.SOURCE_ID,
                        "kb-" + sanitizeFileName(resource.getFilename()))));
        additionalMetadata.put(MedicalKnowledgeMetadataKeys.TEMP_ARTICLE_TITLE,
                stringValue(parseResult.metadata().getOrDefault(MedicalKnowledgeMetadataKeys.TITLE,
                        removeExtension(resource.getFilename()))));
        additionalMetadata.put(MedicalKnowledgeMetadataKeys.TEMP_RESOURCE_FILENAME, resource.getFilename());
        additionalMetadata.put(MedicalKnowledgeMetadataKeys.URI, resourceDescription(resource));

        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata(additionalMetadata)
                .build();
        MarkdownDocumentReader reader = new MarkdownDocumentReader(toProcessedMarkdownResource(resource, parseResult.body()), config);
        return reader.read();
    }

    private List<Document> readText(Resource resource) {
        TextReader reader = new TextReader(resource);
        reader.getCustomMetadata().put(MedicalKnowledgeMetadataKeys.SOURCE_ID, "kb-" + sanitizeFileName(resource.getFilename()));
        reader.getCustomMetadata().put(MedicalKnowledgeMetadataKeys.TEMP_ARTICLE_TITLE, removeExtension(resource.getFilename()));
        reader.getCustomMetadata().put(MedicalKnowledgeMetadataKeys.TEMP_RESOURCE_FILENAME, resource.getFilename());
        reader.getCustomMetadata().put(MedicalKnowledgeMetadataKeys.URI, resourceDescription(resource));
        return reader.read();
    }

    private Resource toProcessedMarkdownResource(Resource originalResource, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayResource(bytes, originalResource.getDescription()) {
            @Override
            public String getFilename() {
                return originalResource.getFilename();
            }
        };
    }

    private String readResourceContent(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException("读取知识库资源失败: " + resourceDescription(resource), ex);
        }
    }

    private FrontMatterParseResult parseFrontMatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return new FrontMatterParseResult(Map.of(), content == null ? "" : content);
        }

        int closing = content.indexOf("\n---", 3);
        if (closing < 0) {
            return new FrontMatterParseResult(Map.of(), content);
        }

        String frontMatter = content.substring(3, closing).trim();
        String body = content.substring(closing + 4).trim();

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String line : frontMatter.split("\\R")) {
            if (!line.contains(":")) {
                continue;
            }
            int separator = line.indexOf(':');
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                metadata.put(key, value);
            }
        }
        return new FrontMatterParseResult(metadata, body);
    }

    private String resourceDescription(Resource resource) {
        try {
            return resource.getURL().toString();
        }
        catch (IOException ex) {
            return resource.getDescription();
        }
    }

    private String sanitizeFileName(String filename) {
        if (filename == null) {
            return "unknown";
        }
        return removeExtension(filename).replaceAll("[^a-zA-Z0-9\\p{IsHan}]+", "-").toLowerCase();
    }

    private String removeExtension(String filename) {
        if (filename == null) {
            return "unknown";
        }
        String clean = filename;
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot);
        }
        return clean;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private record FrontMatterParseResult(Map<String, Object> metadata, String body) {
    }
}
