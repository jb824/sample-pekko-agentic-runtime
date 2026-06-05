# TODO

- Agent memory should evolve beyond raw recent events. Preferred production shape is either:
  - last N chat completions plus a rolling summary, or
  - last N chat completions plus vector/RAG recall where every chat completion is embedded into the vector DB.
- Decide between summary memory and RAG-backed memory before adding a durable memory backend.
- RAG embedding provider should standardize on intfloat E5 rather than OpenAI embeddings. Prefer ONNX/local E5 or an E5-compatible service adapter behind `EmbeddingClient`.
- Add an optional tools-library package as an addon to the Pekko runtime. Runtime should keep only the public tool interfaces and Pekko execution boundary; reusable tools such as `time.now`, web search, arXiv search, RAG retrieval, HTTP/API callers, and future MCP adapters should live in a separate addon module/artifact that clients can depend on and register explicitly.
