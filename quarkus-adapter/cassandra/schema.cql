CREATE KEYSPACE IF NOT EXISTS agent
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS agent.news_summaries (
    event_id uuid PRIMARY KEY,
    tenant_id text,
    article_id text,
    workflow_id text,
    source_event_id text,
    summary text,
    topics text,
    confidence text,
    created_at timestamp
);

CREATE TABLE IF NOT EXISTS agent.news_summaries_by_tenant (
    tenant_id text,
    created_at timestamp,
    event_id uuid,
    article_id text,
    workflow_id text,
    source_event_id text,
    summary text,
    topics text,
    confidence text,
    PRIMARY KEY ((tenant_id), created_at, event_id)
) WITH CLUSTERING ORDER BY (created_at DESC, event_id ASC);
