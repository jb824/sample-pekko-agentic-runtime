package com.example.agent.adapter.grpc;

import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.runtime.grpc.AgentRuntimeHandlerFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;

import java.util.concurrent.CompletionStage;

public final class GrpcServerAdapter {
    private GrpcServerAdapter() {
    }

    public static CompletionStage<ServerBinding> start(
            ActorSystem<?> system,
            String host,
            int port,
            AgentRuntime runtime,
            AgentSystem defaultSystem
    ) {
        AgentRuntimePekkoGrpcService service = new AgentRuntimePekkoGrpcService(runtime, defaultSystem);
        return Http.get(system).newServerAt(host, port)
                .bind(AgentRuntimeHandlerFactory.createWithServerReflection(service, system));
    }
}
