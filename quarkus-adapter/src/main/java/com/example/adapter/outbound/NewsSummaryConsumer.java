package com.example.adapter.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NewsSummaryConsumer {
    private static final Logger LOG = Logger.getLogger(NewsSummaryConsumer.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    CassandraSummaryWriter writer;

    @Incoming("agent-workflow-events-in")
    public void onMessage(String payload) {
        try {
            NewsSummaryGeneratedEvent event = mapper.readValue(payload, NewsSummaryGeneratedEvent.class);
            LOG.infof(
                    "workflow_event event=received topic=agent.workflow.events.v1 event_id=%s tenant_id=%s article_id=%s workflow_id=%s",
                    event.eventId(),
                    event.tenantId(),
                    event.payload().articleId(),
                    event.workflowId()
            );
            writer.write(event);
            LOG.infof(
                    "workflow_event event=stored event_id=%s article_id=%s",
                    event.eventId(),
                    event.payload().articleId()
            );
        } catch (Exception exception) {
            LOG.errorf(exception, "workflow_event event=failed payload=%s", payload);
            throw new IllegalStateException("Failed to consume workflow event", exception);
        }
    }
}
