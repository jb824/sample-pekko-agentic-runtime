package com.example.agent.tool;

import com.example.agent.tool.adapter.ArxivSearchAdapter;
import com.example.agent.tool.adapter.PubMedSearchAdapter;
import com.example.agent.tool.adapter.WebSearchAdapter;
import com.example.agent.tool.service.ArxivSearchToolService;
import com.example.agent.tool.service.PubMedSearchToolService;
import com.example.agent.tool.service.TimeToolService;
import com.example.agent.tool.service.ToolService;
import com.example.agent.tool.service.WebSearchToolService;

import java.time.Clock;

public final class DefaultToolWiring implements ToolWiring {
    @Override
    public ToolService timeToolService() {
        return new TimeToolService(Clock.systemUTC());
    }

    @Override
    public ToolService webSearchToolService() {
        return new WebSearchToolService(new WebSearchAdapter());
    }

    @Override
    public ToolService arxivSearchToolService() {
        return new ArxivSearchToolService(new ArxivSearchAdapter());
    }

    @Override
    public ToolService pubMedSearchToolService() {
        return new PubMedSearchToolService(new PubMedSearchAdapter());
    }
}
