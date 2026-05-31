package com.example.adapter.inbound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;

@Path("/webhook/guardian")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GuardianWebhookResource {
    private static final Logger LOG = Logger.getLogger(GuardianWebhookResource.class);

    @Inject
    CommandEventPublisher publisher;

    @POST
    public Response receive(@Valid GuardianWebhookRequest request) throws Exception {
        LOG.infof(
                "webhook_event event=received tenant_id=%s article_id=%s title=%s url=%s",
                request.tenantId(),
                request.articleId(),
                request.title(),
                request.url()
        );
        String eventId = publisher.publishNewsArticle(
                request.tenantId(),
                "guardian",
                request.articleId(),
                request.title(),
                request.url(),
                Instant.now()
        );
        return Response.ok(new WebhookAck("accepted", eventId)).build();
    }

    public record WebhookAck(String status, String eventId) {
    }
}
