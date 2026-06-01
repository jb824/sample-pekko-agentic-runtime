package com.example.agent.adapter.grpc;

import com.example.agent.runtime.AgentRuntimeService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

import java.io.IOException;

public final class GrpcServerAdapter {
    private GrpcServerAdapter() {
    }

    public static Server start(int port, AgentRuntimeService runtimeService) throws IOException {
        HealthStatusManager health = new HealthStatusManager();
        Server server = NettyServerBuilder.forPort(port)
                .addService(new AgentRuntimeGrpcService(runtimeService))
                .addService(health.getHealthService())
                .build()
                .start();
        return server;
    }
}
