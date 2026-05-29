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
