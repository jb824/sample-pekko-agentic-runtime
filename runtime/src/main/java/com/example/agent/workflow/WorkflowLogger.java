package com.example.agent.workflow;

import org.apache.pekko.actor.typed.javadsl.ActorContext;

public final class WorkflowLogger {
    private WorkflowLogger() {
    }

    public static void event(
            ActorContext<?> context,
            String workflow,
            String requestId,
            int step,
            String event,
            String details
    ) {
        context.getLog().info(
                "workflow_event workflow={} request_id={} step={} event={} {}",
                workflow,
                requestId == null || requestId.isBlank() ? "<unknown>" : requestId,
                step,
                event,
                details == null ? "" : details
        );
    }

    public static void event(
            ActorContext<?> context,
            String workflow,
            String requestId,
            String event,
            String details
    ) {
        event(context, workflow, requestId, 0, event, details);
    }
}
