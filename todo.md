# TODO

- Agent memory should evolve beyond raw recent events. Preferred production shape is either:
  - last N chat completions plus a rolling summary, or
  - last N chat completions plus vector/RAG recall where every chat completion is embedded into the vector DB.
- Decide between summary memory and RAG-backed memory before adding a durable memory backend.
- RAG embedding provider should standardize on intfloat E5 rather than OpenAI embeddings. Prefer ONNX/local E5 or an E5-compatible service adapter behind `EmbeddingClient`.
