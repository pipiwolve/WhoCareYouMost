# MedicalAgent Agent Rules

## Official Docs First
- For any MedicalAgent task involving Spring AI, RAG, ETL pipeline, vector databases, agent hooks, model integration, or retrieval strategy, check the relevant `https://java2ai.com/` documentation before implementation.
- Start from the closest official implementation under the Java2AI docs tree, especially:
  - `https://java2ai.com/integration/rag/`
  - `https://java2ai.com/integration/rag/etl-pipeline/`
  - `https://java2ai.com/integration/rag/vectordbs/`

## Deviation Rule
- If the project intentionally deviates from the official implementation, keep the deviation explicit in the work summary.
- The summary must include:
  - the official documentation link
  - the reason the project keeps a custom implementation

## Elasticsearch-Specific Note
- For Elasticsearch vector store work, prefer the official Spring AI Elasticsearch vector store approach as the baseline:
  - official starter and property prefixes
  - official `ElasticsearchVectorStore` builder/manual configuration
- This project intentionally keeps custom wiring when needed to support:
  - `simple|elasticsearch` vector store switching
  - manifest-based reindex cleanup
  - hybrid retrieval on top of the standard vector store
