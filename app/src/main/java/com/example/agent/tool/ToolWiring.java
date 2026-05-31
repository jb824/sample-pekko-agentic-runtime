package com.example.agent.tool;

import com.example.agent.tool.service.ToolService;

public interface ToolWiring {
    ToolService timeToolService();

    ToolService webSearchToolService();

    ToolService arxivSearchToolService();

    ToolService pubMedSearchToolService();
}
