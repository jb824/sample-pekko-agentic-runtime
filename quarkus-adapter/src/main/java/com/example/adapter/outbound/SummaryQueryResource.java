package com.example.adapter.outbound;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/api/summaries")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class SummaryQueryResource {
    private static final Logger LOG = Logger.getLogger(SummaryQueryResource.class);

    @Inject
    CassandraSummaryWriter writer;

    @GET
    public List<SummaryRecord> latest(
            @QueryParam("tenantId") @DefaultValue("tenant-abc") String tenantId,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        LOG.infof("summary_query event=request tenant_id=%s limit=%d", tenantId, boundedLimit);
        List<SummaryRecord> rows = writer.latestByTenant(tenantId, boundedLimit);
        LOG.infof("summary_query event=response tenant_id=%s count=%d", tenantId, rows.size());
        return rows;
    }
}
