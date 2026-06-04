# Cassandra Application Schema

These CQL files are for agent context application tables only.

They are intentionally separate from Pekko Persistence Cassandra journal and snapshot tables:

- `pekko_agent_journal` is owned by the Pekko persistence plugin.
- `pekko_agent_snapshot` is owned by the Pekko persistence plugin.
- `agent_context` stores memory chunk metadata, summaries/manifests, and context references.

Do not store embeddings, vector indexes, or large transcript artifacts in Pekko persistence tables.
Embeddings belong in the configured vector store. Large raw context artifacts belong in object storage
or a dedicated document/search store.
