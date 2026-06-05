# Cassandra Application Schema

These CQL files are for agent context application tables only.

The `agent_context` keyspace stores memory chunk metadata, summaries/manifests, and context references.

Do not store embeddings, vector indexes, or large transcript artifacts in these tables.
Embeddings belong in the configured vector store. Large raw context artifacts belong in object storage
or a dedicated document/search store.
