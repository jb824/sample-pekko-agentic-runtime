package com.example.agent.tool;

import com.example.agent.tool.service.ToolService;

import java.util.Objects;

public final class ServiceBackedToolWiring implements ToolWiring {
    private final ToolService timeToolService;
    private final ToolService webSearchToolService;
    private final ToolService arxivSearchToolService;
    private final ToolService pubMedSearchToolService;

    public ServiceBackedToolWiring(
            ToolService timeToolService,
            ToolService webSearchToolService,
            ToolService arxivSearchToolService,
            ToolService pubMedSearchToolService
    ) {
        this.timeToolService = Objects.requireNonNull(timeToolService);
        this.webSearchToolService = Objects.requireNonNull(webSearchToolService);
        this.arxivSearchToolService = Objects.requireNonNull(arxivSearchToolService);
        this.pubMedSearchToolService = Objects.requireNonNull(pubMedSearchToolService);
    }

    @Override
    public ToolService timeToolService() {
        return timeToolService;
    }

    @Override
    public ToolService webSearchToolService() {
        return webSearchToolService;
    }

    @Override
    public ToolService arxivSearchToolService() {
        return arxivSearchToolService;
    }

    @Override
    public ToolService pubMedSearchToolService() {
        return pubMedSearchToolService;
    }
}
