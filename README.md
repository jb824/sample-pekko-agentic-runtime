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
- `pubmed.search`

## Event-Driven Architecture

This repo now supports a Kafka event boundary:

- **Producer adapter (Quarkus)**: receives Guardian webhook payloads, validates/normalizes them, and publishes `NewsArticleReceived` to `agent.commands.v1`.
- **Broker**: Kafka (Docker).
- **Consumer/runtime (Pekko)**: consumes `agent.commands.v1`, runs workflow, and publishes `NewsSummaryGenerated` to `agent.workflow.events.v1`.
- **Output adapter (Quarkus)**: consumes `agent.workflow.events.v1` and writes a read model to Cassandra.

## Requirements

- Java 21
- Gradle wrapper from this repo
- Optional: Ollama
- Optional: vLLM with Python/uv

## Build

```bash
./gradlew compileJava
./gradlew test
```

## Run With Defaults

```bash
./gradlew run
```

Run Pekko as Kafka runtime:

```bash
APP_MODE=kafka-runtime \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
KAFKA_INPUT_TOPIC=agent.commands.v1 \
KAFKA_OUTPUT_TOPIC=agent.workflow.events.v1 \
KAFKA_GROUP_ID=pekko-agent-runtime \
AGENT_WORKFLOW=react \
AGENT_TOOLS=time.now,web.search,arxiv.search \
./gradlew run
```

Useful environment variables:

```bash
LLM_BACKEND=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=granite4:3b
AGENT_WORKFLOW=planner-executor
AGENT_TOOLS=time.now
AGENT_PROMPT="Explain Apache Pekko typed actors in one practical paragraph."
```

Example:

```bash
LLM_BACKEND=ollama \
AGENT_WORKFLOW=react \
AGENT_TOOLS=time.now \
AGENT_PROMPT="What time is it now in UTC?" \
./gradlew run
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
vllm serve <model>
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
AGENT_TOOLS=pubmed.search,web.search,arxiv.search \
AGENT_PROMPT="What treatments are available for adenoid cystic carcinoma?" \
./gradlew run
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
AGENT_TOOLS=pubmed.search,web.search,arxiv.search \
AGENT_MAX_TOOLS=2 \
AGENT_MAX_STEPS=4 \
AGENT_PROMPT="What treatments are available for adenoid cystic carcinoma?" \
./scripts/test-vllm-concurrency.sh
```

## Local Event Stack (Kafka + Cassandra + Quarkus + Pekko)

### 1) Run Kafka in Docker

```bash
sudo docker run -d --name kafka \
    -p 127.0.0.1:9092:9092 \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    apache/kafka:3.8.1
```

### 2) Run Cassandra in Docker

```bash
sudo docker run -d --name cassandra-dev -p 9042:9042 cassandra:5
```

### 3) Apply Cassandra schema

```bash
sudo docker exec -i cassandra-dev cqlsh < quarkus-adapter/cassandra/schema.cql
```

### 4) Run Quarkus adapter

Path: `quarkus-adapter/`

```bash
cd quarkus-adapter
./mvnw quarkus:dev
```

Useful Quarkus env vars:

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
CASSANDRA_CONTACT_POINTS=localhost:9042
CASSANDRA_LOCAL_DATACENTER=datacenter1
GUARDIAN_API_KEY=<your_key>
GUARDIAN_POLL_ENABLED=true
GUARDIAN_POLL_INTERVAL=10m
GUARDIAN_TENANT_ID=tenant-default
HACKERNEWS_POLL_ENABLED=true
HACKERNEWS_POLL_INTERVAL=10m
HACKERNEWS_TENANT_ID=tenant-default
```

### 5) Run Pekko runtime in Kafka mode

From repo root:

```bash
APP_MODE=kafka-runtime \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
KAFKA_INPUT_TOPIC=agent.commands.v1 \
KAFKA_OUTPUT_TOPIC=agent.workflow.events.v1 \
KAFKA_GROUP_ID=pekko-agent-runtime \
LLM_BACKEND=ollama \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=granite4:3b \
AGENT_WORKFLOW=react \
AGENT_TOOLS=time.now,web.search,arxiv.search,pubmed.search \
AGENT_MAX_TOOLS=2 \
AGENT_MAX_STEPS=4 \
WORKFLOW_TIMEOUT_SECONDS=180 \
TOOL_TIMEOUT_SECONDS=60 \
./gradlew run
```

Useful Pekko env vars:

```bash
APP_MODE=kafka-runtime
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_INPUT_TOPIC=agent.commands.v1
KAFKA_OUTPUT_TOPIC=agent.workflow.events.v1
KAFKA_GROUP_ID=pekko-agent-runtime
LLM_BACKEND=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=granite4:3b
AGENT_WORKFLOW=react
AGENT_TOOLS=time.now,web.search,arxiv.search,pubmed.search
AGENT_MAX_TOOLS=2
AGENT_MAX_STEPS=4
LLM_TIMEOUT_SECONDS=90
WORKFLOW_TIMEOUT_SECONDS=180
TOOL_TIMEOUT_SECONDS=60
```

Example webhook call:

```bash
curl -X POST http://localhost:8081/webhook/guardian \
  -H "content-type: application/json" \
  -d '{
    "tenantId":"tenant-abc",
    "articleId":"world/2026/example",
    "title":"Example headline",
    "url":"https://www.theguardian.com/world/2026/example"
  }'
```

Multiple prompt variants can be supplied with `|` separators:

```bash
AGENT_PROMPTS="Explain Pekko typed actors.|Summarize vLLM batching.|Search PubMed for adenoid cystic carcinoma treatments." \
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
AGENT_TOOLS=pubmed.search \
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
