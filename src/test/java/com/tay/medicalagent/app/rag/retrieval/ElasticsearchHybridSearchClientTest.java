package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.rag.config.MedicalRagElasticsearchProperties;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchHybridSearchClientTest {

    @Test
    void shouldFallbackToLexicalOnlyWhenVectorSearchFails() throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("vector timeout"));

        RestClient restClient = mock(RestClient.class);
        Response response = mock(Response.class);
        when(response.getEntity()).thenReturn(new StringEntity("""
                {
                  "hits": {
                    "hits": [
                      {
                        "_id": "doc-1",
                        "_score": 8.4,
                        "_source": {
                          "id": "doc-1",
                          "content": "胸痛患者应优先排查急性冠脉综合征。",
                          "metadata": {
                            "sourceId": "kb-chest-pain-triage",
                            "title": "胸痛分诊",
                            "section": "高危信号"
                          }
                        }
                      }
                    ]
                  }
                }
                """, ContentType.APPLICATION_JSON));
        when(restClient.performRequest(any(Request.class))).thenReturn(response);

        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        medicalRagProperties.getRetrieval().setTopK(5);

        MedicalRagElasticsearchProperties elasticsearchProperties = new MedicalRagElasticsearchProperties();
        elasticsearchProperties.setIndexName("medical-rag");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClient", restClient);
        ObjectProvider<RestClient> restClientProvider = beanFactory.getBeanProvider(RestClient.class);

        ElasticsearchHybridSearchClient client = new ElasticsearchHybridSearchClient(
                vectorStore,
                restClientProvider,
                medicalRagProperties,
                elasticsearchProperties
        );

        List<Document> documents = client.search("胸痛 需要立刻就医吗");

        assertEquals(1, documents.size());
        assertEquals("doc-1", documents.getFirst().getId());
        assertTrue(documents.getFirst().getText().contains("急性冠脉综合征"));
        assertEquals("kb-chest-pain-triage", documents.getFirst().getMetadata().get("sourceId"));
        verify(restClient).performRequest(any(Request.class));
    }

    @Test
    void shouldReturnEmptyResultWhenVectorSearchFailsAndLexicalSearchIsUnavailable() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("vector timeout"));

        MedicalRagProperties medicalRagProperties = new MedicalRagProperties();
        MedicalRagElasticsearchProperties elasticsearchProperties = new MedicalRagElasticsearchProperties();
        elasticsearchProperties.setIndexName("medical-rag");

        ObjectProvider<RestClient> restClientProvider = new StaticListableBeanFactory().getBeanProvider(RestClient.class);

        ElasticsearchHybridSearchClient client = new ElasticsearchHybridSearchClient(
                vectorStore,
                restClientProvider,
                medicalRagProperties,
                elasticsearchProperties
        );

        List<Document> documents = client.search("胸痛 需要立刻就医吗");

        assertEquals(List.of(), documents);
    }
}
