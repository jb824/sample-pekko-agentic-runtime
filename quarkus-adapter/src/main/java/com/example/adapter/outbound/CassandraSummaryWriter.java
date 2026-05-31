package com.example.adapter.outbound;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
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
        insertStatement = session.prepare(
                "INSERT INTO agent.news_summaries " +
                        "(event_id, tenant_id, article_id, workflow_id, source_event_id, summary, topics, confidence, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );
        insertByTenantStatement = session.prepare(
                "INSERT INTO agent.news_summaries_by_tenant " +
                        "(tenant_id, created_at, event_id, article_id, workflow_id, source_event_id, summary, topics, confidence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );
        selectByTenantStatement = session.prepare(
                "SELECT event_id, tenant_id, article_id, workflow_id, source_event_id, summary, topics, confidence, created_at " +
                        "FROM agent.news_summaries_by_tenant WHERE tenant_id = ? LIMIT ?"
        );
    }

    public void write(NewsSummaryGeneratedEvent event) {
        Instant now = Instant.now();
        UUID eventUuid = UUID.fromString(event.eventId());
        try {
            BatchStatementBuilder batch = new BatchStatementBuilder(DefaultBatchType.LOGGED);
            batch.addStatement(insertStatement.bind(
                    eventUuid,
                    event.tenantId(),
                    event.payload().articleId(),
                    event.workflowId(),
                    event.sourceEventId(),
                    event.payload().summary(),
                    event.payload().topics(),
                    event.payload().confidence(),
                    now
            ));
            batch.addStatement(insertByTenantStatement.bind(
                    event.tenantId(),
                    now,
                    eventUuid,
                    event.payload().articleId(),
                    event.workflowId(),
                    event.sourceEventId(),
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
                    row.getString("summary"),
                    row.getString("topics"),
                    row.getString("confidence"),
                    row.getInstant("created_at")
            ));
        }
        return rows;
    }
}
