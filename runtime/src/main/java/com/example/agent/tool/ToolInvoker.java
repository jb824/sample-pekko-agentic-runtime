package com.example.agent.tool;

@FunctionalInterface
public interface ToolInvoker {
    void invoke(ToolProtocol.InvokeTool command);
}
