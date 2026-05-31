package com.example.adapter.outbound;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CassandraSummaryWriter {
    private static final Logger LOG = Logger.getLogger(CassandraSummaryWriter.class);

    @Inject
    CqlSession session;

    private PreparedStatement insertStatement;
    private PreparedStatement insertByTenantStatement;
    private PreparedStatement selectByTenantStatement;

    @PostConstruct
    void init() {
        ensureProjectionColumns();
        insertStatement = session.prepare(
                "INSERT INTO agent.news_summaries " +
                        "(event_id, tenant_id, article_id, workflow_id, source_event_id, source, processing_status, summary, topics, confidence, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS"
        );
        insertByTenantStatement = session.prepare(
                "INSERT INTO agent.news_summaries_by_tenant " +
                        "(tenant_id, created_at, event_id, article_id, workflow_id, source_event_id, source, processing_status, summary, topics, confidence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );
        selectByTenantStatement = session.prepare(
                "SELECT event_id, tenant_id, article_id, workflow_id, source_event_id, source, processing_status, summary, topics, confidence, created_at " +
                        "FROM agent.news_summaries_by_tenant WHERE tenant_id = ? LIMIT ?"
        );
    }

    private void ensureProjectionColumns() {
        addColumnIfMissing("agent.news_summaries", "source", "text");
        addColumnIfMissing("agent.news_summaries", "processing_status", "text");
        addColumnIfMissing("agent.news_summaries_by_tenant", "source", "text");
        addColumnIfMissing("agent.news_summaries_by_tenant", "processing_status", "text");
    }

    private void addColumnIfMissing(String table, String column, String type) {
        try {
            session.execute("ALTER TABLE " + table + " ADD " + column + " " + type);
            LOG.infof("cassandra_schema event=column_added table=%s column=%s", table, column);
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("already exists")) {
                return;
            }
            throw exception;
        }
    }

    public void write(NewsSummaryGeneratedEvent event) {
        Instant now = Instant.now();
        UUID eventUuid = UUID.fromString(event.eventId());
        try {
            ResultSet primaryWrite = session.execute(insertStatement.bind(
                    eventUuid,
                    event.tenantId(),
                    event.payload().articleId(),
                    event.workflowId(),
                    event.sourceEventId(),
                    event.source(),
                    "stored",
                    event.payload().summary(),
                    event.payload().topics(),
                    event.payload().confidence(),
                    now
            ));
            if (!primaryWrite.wasApplied()) {
                LOG.infof("cassandra_write event=deduplicated event_id=%s", event.eventId());
                return;
            }
            BatchStatementBuilder batch = new BatchStatementBuilder(DefaultBatchType.LOGGED);
            batch.addStatement(insertByTenantStatement.bind(
                    event.tenantId(),
                    now,
                    eventUuid,
                    event.payload().articleId(),
                    event.workflowId(),
                    event.sourceEventId(),
                    event.source(),
                    "stored",
                    event.payload().summary(),
                    event.payload().topics(),
                    event.payload().confidence()
            ));
            session.execute(batch.build());
        } catch (Exception exception) {
            LOG.errorf(exception, "cassandra_write event=failed event_id=%s article_id=%s",
                    event.eventId(), event.payload().articleId());
            throw exception;
        }
    }

    public List<SummaryRecord> latestByTenant(String tenantId, int limit) {
        List<SummaryRecord> rows = new ArrayList<>();
        for (Row row : session.execute(selectByTenantStatement.bind(tenantId, limit))) {
            rows.add(new SummaryRecord(
                    row.getUuid("event_id"),
                    row.getString("tenant_id"),
                    row.getString("article_id"),
                    row.getString("workflow_id"),
                    row.getString("source_event_id"),
                    row.getString("source"),
                    row.getString("processing_status"),
                    row.getString("summary"),
                    row.getString("topics"),
                    row.getString("confidence"),
                    row.getInstant("created_at")
            ));
        }
        return rows;
    }
}
