# Pekko LLM Agent Runtime

This project is a JVM runtime for LLM-backed agentic workflows using Apache Pekko Typed. Pekko owns orchestration, LLM 
calls, and tools sit behind actor boundaries.  

Current workflows:

- `research`: single research-style LLM call
- `planner-executor`: plan, optional tools, final execution
- `react`: bounded ReAct loop with tool actions and final answer

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

Use the new high-level runtime facade to avoid actor wiring in app code:

```java
import com.example.agent.api.AgentRuntime;
import com.example.agent.workflow.WorkflowTemplate;

try (AgentRuntime runtime = AgentRuntime.builder()
        .workflow(WorkflowTemplate.react())
        .build()) {
    var result = runtime.run("Summarize the latest CDC flu guidance for clinicians.").toCompletableFuture().join();
    System.out.println(result.output());
}
```

Create your own workflow template with clear boundaries (style + limits + tools):

```java
import com.example.agent.workflow.WorkflowStyle;
import com.example.agent.workflow.WorkflowTemplate;

WorkflowTemplate clinicalResearch = WorkflowTemplate.named("clinical-research", WorkflowStyle.PLAN_AND_EXECUTE)
        .tools(java.util.List.of("web.search", "arxiv.search"))
        .maxTools(2)
        .maxToolRetries(1)
        .build();
```

Design intent:
- workflow behavior is declared as a template
- transport/runtime internals stay hidden behind `AgentRuntime`
- tool selection is explicit and decoupled from orchestration code

## Embedded Runtime API

The `runtime` module is the library target (`com.example.agent:pekko-agent-runtime`). Application code embeds the runtime and owns HTTP/gRPC endpoint classes; `PekkoHttpAdapter` lives in `example` as an adapter sample.

```java
import com.example.agent.api.Agent;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTask;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Task;

Agent weather = Agent.named("weather")
        .instructedBy("Answer weather questions using weather tools.")
        .uses("web.search")
        .build();

Agent activity = Agent.named("activity")
        .instructedBy("Recommend activities using the weather context and user preferences.")
        .build();

GatewayAgent planner = GatewayAgent.named("travel-planner")
        .accepts(Task.of("travel.request").maxIterations(5).build())
        .delegatesTo(weather, activity)
        .instructedBy("Coordinate the team and return a concise itinerary.")
        .build();

AgentSystem system = AgentSystem.builder()
        .entrypoint(planner)
        .agents(weather, activity)
        .build();

try (AgentRuntime runtime = AgentRuntime.builder().build()) {
    String taskId = runtime.componentClient()
        .forGatewayAgent(system, java.util.UUID.randomUUID().toString())
        .runSingleTaskAsync(AgentTask.of("activity.request")
                .instructions("Plan an outdoor afternoon in Toronto tomorrow.")
                .build())
        .toCompletableFuture()
        .join();

    var task = runtime.componentClient()
        .forTask(taskId)
        .getAsync()
        .toCompletableFuture()
        .join();
}
```

Example adapter endpoints in this repository:
- `POST /v1/agent/invoke` (default workflow)
- `POST /v1/agents/execute` (client-designed multi-agent system)
- `POST /v1/workflows/execute` (client-designed workflow per request)
- `POST /v1/workflows/research/invoke`
- `POST /v1/workflows/planner-executor/invoke`
- `POST /v1/workflows/react/invoke`

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
- `POST /v1/agent/invoke` JSON body: `{"requestId":"...", "input":"...", "timeoutMs":60000}`

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
./gradlew :example:run
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
AGENT_WORKFLOW=planner-executor
AGENT_TOOLS=time.now
AGENT_TOOL_RETRY_ATTEMPTS=2
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
AGENT_WORKFLOW=react \
AGENT_TOOLS=time.now \
AGENT_PROMPT="What time is it now in UTC?" \
./gradlew :example:run
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
  --max_num_batched_tokens=2048 \
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
AGENT_WORKFLOW=react \
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
AGENT_WORKFLOW=react \
AGENT_TOOLS=web.search,arxiv.search \
AGENT_MAX_TOOLS=2 \
AGENT_MAX_STEPS=4 \
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
AGENT_WORKFLOW=react \
AGENT_TOOLS=web.search \
./scripts/test-vllm-concurrency.sh
```

## Notes

The ReAct workflow is bounded by:

- `AGENT_MAX_STEPS`
- `AGENT_MAX_TOOLS`
- `TOOL_TIMEOUT_SECONDS`
- `WORKFLOW_TIMEOUT_SECONDS`
- `VLLM_MAX_TOKENS`

For biomedical or research-like prompts, ReAct requires source URLs from enabled source tools before returning a final answer.
