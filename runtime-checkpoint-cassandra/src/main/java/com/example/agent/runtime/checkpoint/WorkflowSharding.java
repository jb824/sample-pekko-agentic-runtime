package com.example.agent.runtime.checkpoint;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope;

public final class WorkflowSharding {
    public static final EntityTypeKey<WorkflowEntityActor.Command> ENTITY_TYPE_KEY =
            EntityTypeKey.create(WorkflowEntityActor.Command.class, "WorkflowEntity");

    private WorkflowSharding() {
    }

    public static ActorRef<ShardingEnvelope<WorkflowEntityActor.Command>> init(ActorSystem<?> system) {
        return ClusterSharding.get(system)
                .init(Entity.of(ENTITY_TYPE_KEY, entityContext -> WorkflowEntityActor.create(entityContext.getEntityId())));
    }

    public static EntityRef<WorkflowEntityActor.Command> entityRefFor(ActorSystem<?> system, String workflowId) {
        return ClusterSharding.get(system).entityRefFor(ENTITY_TYPE_KEY, workflowId);
    }
}
