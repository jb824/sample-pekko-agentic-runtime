package com.example.agent.tool.service;

import java.util.Map;

public interface ToolService {
    String execute(Map<String, String> arguments) throws Exception;
}
