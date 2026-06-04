CREATE KEYSPACE IF NOT EXISTS agent_context
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS agent_context.memory_chunks_by_conversation (
  tenant_id text,
  conversation_id text,
  bucket int,
  chunk_index int,
  chunk_id uuid,
  token_count int,
  content text,
  content_hash text,
  created_at timestamp,
  PRIMARY KEY ((tenant_id, conversation_id, bucket), chunk_index)
) WITH CLUSTERING ORDER BY (chunk_index ASC);

CREATE TABLE IF NOT EXISTS agent_context.context_manifest_by_agent (
  tenant_id text,
  agent_id text,
  workflow_id text,
  version bigint,
  summary_id uuid,
  recent_chunk_ids list<uuid>,
  retrieved_chunk_ids list<uuid>,
  object_ref text,
  token_budget int,
  created_at timestamp,
  PRIMARY KEY ((tenant_id, agent_id), workflow_id, version)
) WITH CLUSTERING ORDER BY (workflow_id ASC, version DESC);

CREATE INDEX IF NOT EXISTS context_manifest_by_agent_workflow_idx
ON agent_context.context_manifest_by_agent (workflow_id);
