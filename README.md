# Pekko LLM Agent Runtime

This project is a JVM runtime for LLM-backed agent systems using Apache Pekko Typed. Pekko owns orchestration, LLM
calls, and tool boundaries; client/application code owns workflow composition, endpoints, and structured outputs.

Current LLM backends:

- `ollama`
- `vllm`
- `vllm-grpc`

Current tools:

- `time.now`
- `web.search`
- `arxiv.search`
- `rag.retrieve`

## Simplified Programmatic API

Use the runtime client facade to execute a client-defined agent workflow without wiring Pekko actors in app code:

```java
import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntimeClient;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.Tool;
import com.example.agent.api.GoalDefinition;
import com.example.agent.api.GoalRule;
import com.example.agent.api.AgentWorkflow;
import com.example.agent.api.GatewayAgent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AssistantWorkflow implements AgentWorkflow {
    private static final GoalDefinition ANSWER_QUESTION = GoalDefinition.named("answer.question")
            .describedAs("Answer a user's question directly and cite tool results when available.")
            .template("Answer the following user question:\n{{input}}")
            .maxIterations(2)
            .rule(GoalRule.nonEmptyInstructions())
            .build();

    private final AgentSystem system;

    public AssistantWorkflow() {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly and use available tools when useful.")
                .usesTools(this)
                .memory(AgentMemoryConfig.recentEvents(20))
                .build();

        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(ANSWER_QUESTION)
                .delegatesTo(assistant)
                .build();

        this.system = AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    public AgentSystem system() {
        return system;
    }

    public GoalDefinition goalDefinition() {
        return ANSWER_QUESTION;
    }

    public java.time.Duration timeout() {
        return java.time.Duration.ofSeconds(60);
    }

    @Tool(description = "Return current date in yyyy-MM-dd format")
    private String getCurrentDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}

try (AgentRuntimeClient client = AgentRuntimeClient.create()) {
    var result = client.run(
            AgentRunContext.tenant("default"),
            new AssistantWorkflow(),
            "What date is it?"
    ).toCompletableFuture().join();

    System.out.println(result.output());
}
```

For lower-level control, build the `AgentSystem` and `Goal` directly:

```java
import com.example.agent.api.Agent;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.Goal;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GatewayAgent;

Agent researcher = Agent.named("researcher")
        .instructedBy("Answer carefully and cite tool results when available.")
        .uses("web.search", "arxiv.search")
        .build();

GatewayAgent gateway = GatewayAgent.named("gateway")
        .accepts(Goal.of("research.request").maxIterations(1).build())
        .delegatesTo(researcher)
        .instructedBy("Delegate to the researcher and return the final answer.")
        .build();

AgentSystem system = AgentSystem.builder()
        .entrypoint(gateway)
        .agent(researcher)
        .build();

try (AgentRuntime runtime = AgentRuntime.builder().build()) {
    var result = runtime.run(
            system,
            GoalRequest.of("research.request")
                    .instructions("Summarize the latest CDC flu guidance for clinicians.")
                    .build()
    ).toCompletableFuture().join();
    System.out.println(result.output());
}
```

Design intent:
- client code defines agents, task contracts, workflows, and endpoints
- client code defines and registers tool implementations
- transport/runtime internals stay hidden behind `AgentRuntime`
- tool selection remains explicit and decoupled from orchestration code
- the built-in `DefaultAgentSystemExecutorActor` is runtime infrastructure for executing client-defined agent systems, not a workflow catalog

## Embedded Runtime API

The `runtime` module is the library target (`com.example.agent:pekko-agent-runtime`). It contains agent execution, goal lifecycle, tools, LLM orchestration, and the small Pekko HTTP bootstrap API under `com.example.agent.http`, so clients only need one runtime dependency.

```java
import com.example.agent.api.Agent;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.Goal;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GatewayAgent;

Agent weather = Agent.named("weather")
        .instructedBy("Answer weather questions using weather tools.")
        .uses("web.search")
        .build();

Agent activity = Agent.named("activity")
        .instructedBy("Recommend activities using the weather context and user preferences.")
        .build();

GatewayAgent planner = GatewayAgent.named("travel-planner")
        .accepts(Goal.of("travel.request").maxIterations(5).build())
        .delegatesTo(weather, activity)
        .instructedBy("Coordinate the team and return a concise itinerary.")
        .build();

AgentSystem system = AgentSystem.builder()
        .entrypoint(planner)
        .agents(weather, activity)
        .build();

try (AgentRuntime runtime = AgentRuntime.builder().build()) {
    String goalId = runtime.componentClient()
        .forGatewayAgent(system, java.util.UUID.randomUUID().toString())
        .runSingleGoalAsync(GoalRequest.of("activity.request")
                .instructions("Plan an outdoor afternoon in Toronto tomorrow.")
                .build())
        .toCompletableFuture()
        .join();

    var goal = runtime.componentClient()
        .forGoal(goalId)
        .getAsync()
        .toCompletableFuture()
        .join();
}
```

Example HTTP endpoints in this repository:
- `POST /v1/agents/execute` (client-designed multi-agent system)
- `POST /v1/agents/goals`
- `GET /v1/agents/goals/{goalId}`

## Post-Execution Consumers

Clients can register runtime consumers for non-blocking post-execution work such as evaluation agents, audit trails, metrics, or guardrails. The runtime only provides delivery infrastructure; each client owns its consumer logic and idempotency.

```java
try (AgentRuntime runtime = AgentRuntime.builder()
        .consumer(new EvaluationConsumer((input, output) ->
                new EvaluationConsumer.EvaluationResult(true, 1.0, "")))
        .build()) {
    runtime.run(system, GoalRequest.of("agent.request").instructions("Hello").build());
}
```

Consumer events are fire-and-forget after the `AgentResult` is sent to the caller. Delivery is at least once; use `consumerId + requestId` as the idempotency key. Configure the shared consumer executor with `CONSUMER_THREADS` / `runtime.consumer_threads`, bound each consumer worker queue with `CONSUMER_QUEUE_SIZE` / `runtime.consumer_queue_size`, and set the default processing deadline with `CONSUMER_PROCESSING_TIMEOUT_SECONDS` / `timeouts.consumer_processing_seconds`.

## Pluggable RAG Runtime

RAG is an external knowledge subsystem, not Pekko Persistence storage. The runtime uses provider-neutral interfaces for:

- `EmbeddingClient` — embeds query/document text and exposes model/dimensions
- `VectorStore` — upserts chunks, deletes by tenant/document, and performs tenant-filtered similarity search
- `RagRetriever` — retrieves scored chunks for a tenant/security context
- `RagContextBuilder` — formats retrieved chunks into bounded prompt context with citations

Query-time flow:

```text
Agent request
  -> DefaultAgentSystemExecutorActor
  -> RagRuntimeActor, if RAG_ENABLED=true
  -> EmbeddingClient + VectorStore
  -> Retrieved knowledge context
  -> LLM prompt construction
```

Ingestion flow:

```text
RagRuntimeActor.IndexDocument / ReindexDocument / DeleteDocument
  -> DocumentChunker
  -> EmbeddingClient.embedBatch
  -> VectorStore.upsert/delete
```

Current built-in provider is local `in-memory` with deterministic hash embeddings for tests and development. Production embedding should use an intfloat E5-family provider behind `EmbeddingClient` (for example ONNX/local E5 or an E5-compatible embedding service). pgvector, Qdrant, or other stores should be added behind `VectorStore`. If pgvector is added and shares a PostgreSQL instance with Pekko Persistence R2DBC, it must use separate schemas/tables/migrations and must not modify Pekko journal, snapshot, or projection tables.

RAG config/env:

```text
RAG_ENABLED=false
RAG_EMBEDDING_PROVIDER=in-memory
RAG_EMBEDDING_MODEL=intfloat/multilingual-e5-large-instruct
RAG_EMBEDDING_DIMENSIONS=384
RAG_VECTOR_STORE=in-memory
RAG_TOP_K=5
RAG_MIN_SCORE=0.0
RAG_MAX_CONTEXT_CHARS=4000
RAG_CHUNK_MAX_CHARS=1200
RAG_CHUNK_OVERLAP_CHARS=120
PGVECTOR_*=<only for future pgvector provider>
```

Security/ops notes:

- Retrieval is always called with tenant context; the default vector store never searches across tenants.
- Runtime logs do not log raw document chunks, embeddings, API keys, or full prompts.
- Retrieval failures degrade gracefully by omitting RAG context and continuing the agent run.
- Telemetry spans are emitted for retrieval and indexing latency, chunk counts, score range, and failures.

## Durable Agent Context

Durable workflow checkpointing is intentionally out of the core runtime for now. The current runtime keeps execution embedded and simple; production persistence should be reintroduced only when the workflow/entity model is stable enough to justify a dedicated persistence module.

The Cassandra app schema lives in `runtime/src/main/resources/db/cassandra` for context manifests and memory chunk metadata/content. It is intentionally separate from any future Pekko journal/snapshot schema. Application-table CQL is applied manually until a migration tool is chosen.

Apply the app schema with `cqlsh` if available:

```bash
cqlsh 127.0.0.1 9042 -f runtime/src/main/resources/db/cassandra/001_agent_context.cql
```

If Cassandra is running in Docker and `cqlsh` is only available inside the container:

```bash
docker exec -i cassandra-dev cqlsh < runtime/src/main/resources/db/cassandra/001_agent_context.cql
```

## Easy HTTP Bootstrap

Use `com.example.agent.http.AgentHttpServer` when you want a small Pekko HTTP route-based server for client-defined agent endpoints:

```java
import com.example.agent.api.Agent;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Goal;
import com.example.agent.api.GoalRequest;
import com.example.agent.http.AgentHttpServer;

Agent researcher = Agent.named("researcher")
        .instructedBy("Answer carefully and use tools when appropriate.")
        .uses("web.search", "arxiv.search")
        .build();

GatewayAgent gateway = GatewayAgent.named("gateway")
        .accepts(Goal.of("research.request").maxIterations(1).build())
        .delegatesTo(researcher)
        .instructedBy("Delegate to the researcher and return the final answer.")
        .build();

AgentSystem system = AgentSystem.builder()
        .entrypoint(gateway)
        .agent(researcher)
        .build();

try (AgentRuntime runtime = AgentRuntime.builder().build();
     AgentHttpServer server = AgentHttpServer.builder()
             .runtime(runtime)
             .port(8080)
             .syncEndpoint("/v1/research", system, "research.request")
             .asyncEndpoint("/v1/research/goals", system, "research.request")
             .build()) {
    server.start().toCompletableFuture().join();
}
```

Request body format for sync/async endpoints:

```json
{
  "requestId": "optional-id",
  "input": "Summarize the latest CDC flu guidance for clinicians.",
  "timeoutMs": 60000
}
```

## Requirements

- Java 21
- Gradle wrapper from this repo
- Optional: Ollama
- Optional: vLLM with Python/uv

## Build

```bash
./gradlew --no-configuration-cache compileJava test
```

## Module Commands

Compile the publishable runtime library:

```bash
./gradlew --no-configuration-cache :runtime:compileJava
```

Publish the runtime library to your local Maven repository:

```bash
./gradlew --no-configuration-cache :runtime:publishToMavenLocal
```

Run the example application module:

```bash
./gradlew --no-configuration-cache :example:run
```

Run the example RAG retrieval service:

```bash
./gradlew --no-configuration-cache :example:runRagService
```

Run the example RAG eval runner:

```bash
./gradlew --no-configuration-cache :example:runRagEval
```

## Run With Defaults

```bash
./gradlew --no-configuration-cache :example:run
```

## Run As Service (HTTP + gRPC)

```bash
AGENT_RUN_MODE=server \
AGENT_ENABLE_HTTP=true \
AGENT_ENABLE_GRPC=true \
AGENT_HTTP_HOST=0.0.0.0 \
AGENT_HTTP_PORT=8080 \
AGENT_GRPC_PORT=8081 \
./gradlew --no-configuration-cache :example:run
```

HTTP endpoints:

- `GET /health`
- `POST /v1/agents/execute`
- `POST /v1/agents/goals`
- `GET /v1/agents/goals/{goalId}`

## Decoupled RAG Retrieval Service

Run a separate retrieval/indexing service:

```bash
RAG_SERVICE_PORT=8090 \
QDRANT_URL=http://localhost:6333 \
RAG_COLLECTION=customer_profiles \
RAG_EMBEDDING_MODE=openai-compatible \
RAG_EMBEDDING_URL=http://localhost:8000/v1 \
RAG_EMBEDDING_API_KEY=EMPTY \
RAG_EMBEDDING_MODEL=intfloat/multilingual-e5-large-instruct \
./gradlew :example:runRagService
```

Index a profile:

```bash
curl -X POST http://localhost:8090/v1/ingest/profile \
  -H 'content-type: application/json' \
  -d '{"tenantId":"default","customerId":"C001","name":"Customer 001","profileText":"Customer C001 is interested in tornado weather analytics and climate risk products.","tags":["weather","risk"]}'
```

Ingest multiple profiles/documents (same tenant):

```bash
curl -X POST http://localhost:8090/v1/ingest/profile \
  -H 'content-type: application/json' \
  -d '{"tenantId":"default","customerId":"C002","name":"Customer 002","profileText":"Customer C002 focuses on aerospace supply chain resilience and vendor risk scoring.","tags":["aerospace","risk"]}'

curl -X POST http://localhost:8090/v1/ingest/profile \
  -H 'content-type: application/json' \
  -d '{"tenantId":"default","customerId":"C003","name":"Customer 003","profileText":"Customer C003 tracks oncology trial news and treatment evidence updates.","tags":["biomed","clinical"]}'
```

Ingest for a different tenant (isolated retrieval scope):

```bash
curl -X POST http://localhost:8090/v1/ingest/profile \
  -H 'content-type: application/json' \
  -d '{"tenantId":"tenant-b","customerId":"C001","name":"Tenant B Customer 001","profileText":"Customer is interested in logistics route optimization and fleet telemetry.","tags":["logistics"]}'
```

Retrieve:

```bash
curl -X POST http://localhost:8090/v1/retrieve \
  -H 'content-type: application/json' \
  -d '{"tenantId":"default","query":"What is customer C001 interested in?","topK":5,"collection":"customer_profiles"}'
```

Tenant-isolation check (query tenant-b only returns tenant-b docs):

```bash
curl -X POST http://localhost:8090/v1/retrieve \
  -H 'content-type: application/json' \
  -d '{"tenantId":"tenant-b","query":"What is customer C001 interested in?","topK":5,"collection":"customer_profiles"}'
```

Use in agent runtime:

```bash
AGENT_TOOLS=rag.retrieve,time.now \
RAG_RETRIEVAL_URL=http://localhost:8090 \
AGENT_TENANT_ID=default \
./gradlew :client:run
```

Test-only local corpus auto-ingest on app startup (`./gradlew :example:run`):

1. Put files under a local directory, for example `config/local-corpus`:
   - supported: `.md`, `.txt`, `.pdf`, `.doc`, `.docx`
2. Enable in `config/agent.yaml`:

```yaml
rag:
  auto_ingest:
    enabled: true
    directory: config/local-corpus
```

3. Start retrieval service and app runtime:

```bash
./gradlew :example:runRagService
./gradlew :example:run
```

The app will ingest each file as a profile document into the configured tenant/collection for local testing only.

Embedding modes:

- `RAG_EMBEDDING_MODE=onnx` using local `intfloat/e5-small-v2` ONNX + tokenizer assets
- `RAG_EMBEDDING_MODE=openai-compatible` for remote embedding endpoints

For ONNX mode, place model assets locally (example layout):

```text
models/e5-small-v2/model.onnx
models/e5-small-v2/tokenizer.json
```

## RAG Evals

Run retrieval evals against the retrieval service:

```bash
RAG_RETRIEVAL_URL=http://localhost:8090 \
RAG_EVAL_DATASET=config/rag-eval.json \
RAG_EVAL_TOP_K=5 \
./gradlew :example:runRagEval
```

Runtime config is now YAML-first with env overrides:

- default file: `config/agent.yaml`
- override file path: `AGENT_CONFIG_FILE=/path/to/agent.yaml`
- environment variables still override YAML values
- if `AGENT_PROMPT`, `AGENT_PROMPTS`, or `AGENT_PROMPTS_FILE` is set in your shell, YAML `prompts.*` values will be ignored

Useful environment variables:

```bash
LLM_BACKEND=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=granite4:3b
AGENT_TOOLS=time.now
LLM_THREADS=4
LLM_QUEUE_SIZE=32
AGENT_PROMPT="Explain Apache Pekko typed actors in one practical paragraph."
```

RAG/Qdrant config keys are also read from `config/agent.yaml` (env vars still override):

- `rag.retrieval_url`
- `rag.retrieval_api_key`
- `rag.collection`
- `rag.tenant_id`
- `rag.embedding.mode|url|api_key|model`
- `rag.embedding.onnx.model_uri|tokenizer_uri`
- `rag.service.port|timeout_ms`
- `rag.auto_ingest.enabled|directory`
- `qdrant.url|api_key`

Example:

```bash
LLM_BACKEND=ollama \
AGENT_TOOLS=time.now \
AGENT_PROMPT="What time is it now in UTC?" \
./gradlew --no-configuration-cache :example:run
```

```bash
LLM_BACKEND=ollama \
AGENT_TOOLS=time.now \
AGENT_PROMPT="Which month of the year has the letter 'X' in it?" \
./gradlew --no-configuration-cache :client:run
```

## Set Up vLLM

Create a Python environment and install vLLM:

```bash
uv venv
source .venv/bin/activate
uv pip install vllm
```

Start vLLM:

```bash
vllm serve ibm-granite/granite-4.1-3b \
  --dtype auto \  
  --gpu-memory-utilization 0.9 \  
  --max-model-len 2048 \  
  --max-num-seqs 4 \ 
  --max-num-batched-tokens=2048 \
  --quantization bitsandbytes # (optional) --grpc
```

Check the served model name:

```bash
curl http://localhost:8000/v1/models
```

Use the exact served model ID:

```bash
LLM_BACKEND=vllm \
VLLM_BASE_URL=http://localhost:8000/v1 \
VLLM_MODEL=ibm-granite/granite-4.1-3b \
VLLM_API_TYPE=chat \
AGENT_TOOLS=web.search,arxiv.search \
AGENT_PROMPT="What treatments are available for adenoid cystic carcinoma?" \
./gradlew :example:run
```

## Concurrent vLLM Test

The script checks the vLLM endpoint, runs the app with increasing request counts, and prints `BENCH` lines from runtime metrics.

```bash
CONCURRENCY_LEVELS="1 2 4 8" \
LLM_BACKEND=vllm \
VLLM_BASE_URL=http://localhost:8000/v1 \
VLLM_MODEL=ibm-granite/granite-4.1-3b \
VLLM_API_TYPE=chat \
VLLM_MAX_TOKENS=256 \
AGENT_TOOLS=web.search,arxiv.search \
AGENT_PROMPT="What treatments are available for adenoid cystic carcinoma?" \
./scripts/test-vllm-concurrency.sh
```

Multiple prompt variants can be supplied with `|` separators:

```bash
AGENT_PROMPTS="Explain Pekko typed actors.|Summarize vLLM batching.|Search the web for adenoid cystic carcinoma treatments." \
CONCURRENCY_LEVELS="3" \
./scripts/test-vllm-concurrency.sh
```

Or from a file:

```bash
AGENT_PROMPTS_FILE=prompts.txt ./scripts/test-vllm-concurrency.sh
```

## vLLM gRPC

The gRPC backend uses the lower-level vLLM engine protocol. It requires local tokenizer decoding.

```bash
LLM_BACKEND=vllm-grpc \
VLLM_GRPC_HOST=localhost \
VLLM_GRPC_PORT=8000 \
VLLM_TOKENIZER_PATH=~/.cache/huggingface/hub/models--ibm-granite--granite-4.1-3b/snapshots/ \
AGENT_TOOLS=web.search \
./scripts/test-vllm-concurrency.sh
```

## Notes

The runtime’s LLM/tool execution is bounded by:

- `LLM_THREADS`
- `LLM_QUEUE_SIZE`
- `TOOL_THREADS`
- `TOOL_QUEUE_SIZE`
- `MAX_CONCURRENT_REQUESTS`
- `TOOL_TIMEOUT_SECONDS`
- `WORKFLOW_TIMEOUT_SECONDS`
- `VLLM_MAX_TOKENS`
