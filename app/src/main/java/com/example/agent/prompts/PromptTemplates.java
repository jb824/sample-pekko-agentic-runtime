package com.example.agent.prompts;

public final class PromptTemplates {
    private PromptTemplates() {
    }

    public static String researchPrompt(String topic) {
        return """
                You are a careful research assistant.

                Research the following topic:
                %s

                Return:
                1. A concise explanation
                2. Key design implications
                3. Risks or caveats
                4. A short recommendation

                Keep the answer grounded and practical.
                """.formatted(topic);
    }

    public static String plannerPrompt(String request, String availableTools) {
        return """
                You are a planning agent for a local JVM agent runtime.

                Create a short execution plan for this request:
                %s

                Return this tiny format only:
                PLAN:
                1. ...
                2. ...
                3. ...
                TOOLS: comma-separated tool names or none

                Available tools:
                %s

                Keep each step concrete and bounded.
                Preserve product and project names exactly as written.
                """.formatted(request, availableTools);
    }

    public static String executorPrompt(String request, String plan, String toolContext) {
        return """
                You are an executor agent.

                User request:
                %s

                Plan:
                %s

                Tool context:
                %s

                Execute the plan and return a concise final answer.
                Include practical tradeoffs and caveats when relevant.
                If tool context contains Resources with URL fields, include a short Sources section.
                Sources must be exact URLs copied from URL fields in tool context.
                Do not cite generic phrases like "web search results" or "arXiv search"; cite URLs only.
                Preserve product and project names exactly as written.
                Do not rename Apache Pekko or describe it as another project.
                """.formatted(request, plan, toolContext);
    }

    public static String reactPrompt(
            String request,
            String allowedTools,
            int stepNumber,
            int maxSteps,
            String observations
    ) {
        return """
                You are controlling a bounded tool-using agent loop.

                User request:
                %s

                Allowed tools:
                %s

                Observations so far:
                %s

                Step %d of %d.

                Return exactly one of these formats.

                ACTION
                tool=<|one allowed tool name|>
                query=<|short query or input|>

                FINAL
                <|concise final answer|>

                Rules:
                - Do not use JSON.
                - Do not include extra headings.
                - Do not call tools outside the allowed list.
                - Use FINAL when you have enough information.
                - If observations contain URL fields, include exact URLs in FINAL sources.
                """.formatted(request, allowedTools, observations, stepNumber, maxSteps);
    }

    public static String reactFinalPrompt(String request, String observations) {
        return """
                You are finishing a bounded tool-using agent loop.

                User request:
                %s

                Observations:
                %s

                Return exactly:

                FINAL
                <concise final answer>

                Rules:
                - Do not call another tool.
                - Do not use ACTION.
                - Use the observations directly.
                - If observations contain URL fields, include exact URLs in FINAL sources.
                """.formatted(request, observations);
    }
}
