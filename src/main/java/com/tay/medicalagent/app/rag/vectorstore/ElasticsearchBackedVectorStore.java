package com.tay.medicalagent.app.rag.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Optional;

/**
 * Elasticsearch 向量存储委托。
 * <p>
 * 用于在手动完成底层 store 的初始化校验后，再以普通 {@link VectorStore} Bean 形式暴露给 Spring 容器，
 * 避免容器再次触发 {@code InitializingBean} 生命周期并把连接失败抛回启动链路。
 */
public final class ElasticsearchBackedVectorStore implements VectorStore {

    private final ElasticsearchVectorStore delegate;

    public ElasticsearchBackedVectorStore(ElasticsearchVectorStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        delegate.delete(ids);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest searchRequest) {
        return delegate.similaritySearch(searchRequest);
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }
}
