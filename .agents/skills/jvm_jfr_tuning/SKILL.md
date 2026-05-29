---
name: JVM Tuning
description: Tune JVM using Java Flight Recorder.
---

# Skill: JVM Tuning with JFR and JVM Flags

## Purpose

Use this skill to tune a JVM service using evidence from Java Flight Recorder (JFR), GC logs, runtime metrics, and controlled JVM flag changes.

This skill is **procedural memory**. It defines the repeatable process for diagnosing JVM performance and applying JVM/runtime tuning safely.

For the Pekko + LangChain4j + Ollama agent runtime, use this skill when investigating:

- High p95/p99 latency
- GC pauses
- High allocation rate
- Heap growth
- Out-of-memory conditions
- Thread starvation
- Actor dispatcher saturation
- Blocking calls leaking onto actor dispatchers
- Slow startup or warmup
- Container memory instability
- Tenant noisy-neighbor issues

---

## Core Principle

Do **not** tune JVM flags first.

Tune in this order:

```text
1. Confirm the workload
2. Record evidence with JFR and metrics
3. Identify the bottleneck
4. Change one thing
5. Re-run the same workload
6. Compare before/after
7. Keep, revert, or refine
```

JVM tuning is not a collection of magic flags. It is a measurement loop.

---

## What This Skill Produces

A JVM tuning pass should produce:

```text
jfr/
  baseline.jfr
  experiment-001.jfr
  experiment-002.jfr

logs/
  gc-baseline.log
  gc-experiment-001.log

notes/
  tuning-notes.md
  hypothesis.md
  before-after.md
```

Each experiment must record:

```text
Workload:
JDK version:
Container limit:
Heap flags:
GC:
Dispatcher/thread settings:
LLM mode:
Observed issue:
Hypothesis:
Change made:
Result:
Keep/revert:
Next step:
```

---

## Required Tools

Minimum:

```bash
java --version
jcmd
jfr
jps
```

Recommended:

```text
JDK Mission Control
Java Flight Recorder
GC logs
Prometheus / Grafana
OpenTelemetry
JFR CLI views
JMH for JVM microbenchmarks
k6 or Gatling for service load tests
```

For containers:

```bash
docker stats
kubectl top pod
kubectl logs
```

For Linux hosts:

```bash
top
htop
pidstat
iostat
vmstat
```

---

## Baseline JVM Settings for the MVP

For a simple Pekko agent runtime, start conservative.

Example local JVM flags:

```bash
-Xms512m
-Xmx2g
-Xlog:gc*,safepoint:file=logs/gc.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=jfr/startup.jfr,dumponexit=true,settings=profile
```

For containerized services, prefer explicit memory sizing:

```bash
-XX:InitialRAMPercentage=50
-XX:MaxRAMPercentage=70
-Xlog:gc*,safepoint:file=/app/logs/gc.log:time,uptime,level,tags
```

Use fixed `-Xms`/`-Xmx` only when you know the service's memory envelope.

---

## JFR Collection Patterns

### Pattern 1 — Start Recording at JVM Startup

Use this for startup, warmup, and reproduction tests.

```bash
java \
  -XX:StartFlightRecording=filename=jfr/baseline.jfr,dumponexit=true,settings=profile \
  -Xlog:gc*,safepoint:file=logs/gc-baseline.log:time,uptime,level,tags \
  -jar app.jar
```

Use `settings=profile` for deeper analysis during testing.

Use `settings=default` for lower overhead and long-running capture.

---

### Pattern 2 — Attach to a Running JVM with `jcmd`

Find the JVM:

```bash
jcmd
```

Start recording:

```bash
jcmd <PID> JFR.start name=agent-runtime settings=profile filename=jfr/live.jfr
```

Check recording:

```bash
jcmd <PID> JFR.check
```

Dump without stopping:

```bash
jcmd <PID> JFR.dump name=agent-runtime filename=jfr/live-dump.jfr
```

Stop and write recording:

```bash
jcmd <PID> JFR.stop name=agent-runtime filename=jfr/live-final.jfr
```

---

### Pattern 3 — Capture a Short Spike Window

Use this when p99 latency spikes or a tenant flood occurs.

```bash
jcmd <PID> JFR.start name=spike settings=profile duration=120s filename=jfr/spike.jfr
```

Then run the load scenario immediately.

---

### Pattern 4 — Continuous Low-Overhead Recording

Use this later in staging or production-like environments.

```bash
-XX:StartFlightRecording=name=continuous,settings=default,disk=true,maxage=30m,maxsize=512m,dumponexit=true,filename=/app/jfr/continuous.jfr
```

Do not start with continuous production recording until you understand file size, disk behavior, and data sensitivity.

---

## What to Inspect in JFR

Use JDK Mission Control or the `jfr` command-line tool.

Key views:

```text
Method profiling
Allocation pressure
Garbage collection
Object statistics
Thread states
Lock instances
Socket I/O
File I/O
Exceptions
CPU usage
Safepoints
Native memory indicators
```

For a Pekko agent runtime, also correlate with application metrics:

```text
workflow latency
actor mailbox depth
active workflows
LLM request latency
tokens/sec
timeouts
retries
tenantId
agentType
dispatcher name
```

---

## Symptom → Evidence → Likely Fix

### Symptom: High p99 latency

Look for:

```text
JFR thread states
GC pauses
safepoints
blocked threads
long socket reads
slow LLM calls
mailbox growth
```

Possible fixes:

```text
move blocking calls off actor dispatcher
use async/streaming LLM calls
reduce prompt/context size
add backpressure
bound concurrency per tenant
tune dispatcher sizes
increase heap only if GC pressure proves memory shortage
```

Do not immediately increase heap.

---

### Symptom: Frequent GC

Look for:

```text
high allocation rate
short-lived object churn
large JSON/prompt strings
excessive message copies
temporary collections
large response aggregation
```

Possible fixes:

```text
reduce allocations
reuse buffers only where safe
stream responses instead of building huge strings
reduce serialization churn
increase heap if allocation pressure is expected
consider GC tuning only after allocation analysis
```

---

### Symptom: Long GC pauses

Look for:

```text
pause duration
heap occupancy before/after GC
humongous allocations
old generation pressure
promotion failures
safepoints
```

Possible fixes:

```text
right-size heap
avoid giant strings/byte arrays
review prompt/context size
consider ZGC for latency-sensitive services
consider G1 tuning only after baseline
```

---

### Symptom: OutOfMemoryError

Look for:

```text
heap growth over time
actor count growth
mailbox sizes
retained workflow state
conversation memory
large prompt history
unbounded caches
thread count
direct memory
```

Possible fixes:

```text
bound mailboxes
cap actor/workflow lifetime
cap conversation memory
enforce tenant quotas
limit prompt/context size
fix leaks before increasing heap
use heap dump only if needed
```

---

### Symptom: High CPU but low throughput

Look for:

```text
busy loops
retry storms
serialization hotspots
JSON parsing overhead
logging overhead
excessive actor chatter
large prompt construction
```

Possible fixes:

```text
reduce retries
batch where appropriate
avoid chatty actor protocols
reduce serialization frequency
sample logs
profile hot methods
```

---

### Symptom: Thread starvation

Look for:

```text
many BLOCKED / WAITING / TIMED_WAITING threads
actor dispatcher threads stuck in HTTP/LLM calls
ForkJoinPool saturation
blocking calls in actor receive handlers
```

Possible fixes:

```text
never block default actor dispatcher
use async client where possible
isolate blocking fallback in bounded executor
set backpressure at gateway
limit per-tenant concurrent LLM calls
```

---

## GC Choice Guide

### Default: G1GC

Use G1 unless evidence says otherwise.

Good default for:

```text
general services
Pekko applications
moderate heaps
balanced latency/throughput
containerized JVM apps
```

Common baseline:

```bash
-XX:+UseG1GC
-Xlog:gc*,safepoint:file=logs/gc.log:time,uptime,level,tags
```

Often you do not need to specify `UseG1GC` on modern JDKs because G1 is commonly the default, but specifying it can make experiments explicit.

---

### Low-Latency Option: ZGC

Consider ZGC when:

```text
GC pause time is the bottleneck
heap is large
p99/p999 latency matters
you can accept possible throughput tradeoffs
you have benchmark evidence
```

Example:

```bash
-XX:+UseZGC
-Xlog:gc*,safepoint:file=logs/gc-zgc.log:time,uptime,level,tags
```

On newer JDKs, generational ZGC may be available:

```bash
-XX:+UseZGC
-XX:+ZGenerational
```

Only use after confirming support in the chosen JDK.

---

### Do Not Start With Exotic GC Settings

Avoid starting with:

```bash
-XX:MaxGCPauseMillis=...
-XX:G1HeapRegionSize=...
-XX:InitiatingHeapOccupancyPercent=...
-XX:ParallelGCThreads=...
-XX:ConcGCThreads=...
```

These can help, but they can also make things worse.

First determine:

```text
Is GC actually the bottleneck?
Is the heap too small?
Is allocation rate too high?
Is the workload bursty?
Is p99 caused by LLM/network instead?
```

---

## Heap Sizing Guide

### Local Development

```bash
-Xms512m
-Xmx2g
```

### Small Container

For a 1 GiB container:

```bash
-XX:InitialRAMPercentage=40
-XX:MaxRAMPercentage=70
```

### Larger Container

For a 4–8 GiB container:

```bash
-XX:InitialRAMPercentage=40
-XX:MaxRAMPercentage=70
```

Then measure:

```text
heap used after warmup
heap used under sustained load
GC frequency
old-gen occupancy
native memory
thread count
direct buffers
container RSS
```

Do not set heap equal to the container limit. The JVM also needs memory for:

```text
metaspace
thread stacks
code cache
direct buffers
JFR buffers
native libraries
TLS/network buffers
off-heap allocations
```

---

## Container JVM Checklist

Always record:

```text
container memory limit
container CPU limit
JDK version
heap flags
GC
thread count
direct memory usage
RSS
OOMKilled events
```

Recommended initial container flags:

```bash
-XX:MaxRAMPercentage=70
-XX:InitialRAMPercentage=40
-Xlog:gc*,safepoint:file=/app/logs/gc.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=/app/jfr/startup.jfr,dumponexit=true,settings=default
```

If using Kubernetes, ensure writable volumes for:

```text
/app/logs
/app/jfr
/tmp
```

---

## Pekko-Specific JVM Tuning Notes

Most Pekko performance issues are not fixed with JVM flags.

Check these first:

```text
actor topology
dispatcher configuration
blocking isolation
bounded mailboxes
supervision behavior
message size
serialization
cluster sharding distribution
per-tenant throttling
```

### Actor Dispatcher Rule

Do not run LLM calls on the default actor dispatcher.

Preferred:

```text
actor receives request
actor starts async LLM call
actor returns immediately
LLM result arrives as message
```

Fallback:

```text
actor delegates synchronous LLM call to bounded blocking executor
result is piped back to actor
```

Bad:

```text
actor receive handler directly waits for model.chat(...)
```

### JVM Evidence to Look For

In JFR, look for actor dispatcher threads blocked on:

```text
HTTP calls
LangChain4j calls
Ollama calls
file I/O
synchronized locks
sleep
CompletableFuture.get()
join()
await()
```

If found, fix the design before changing JVM flags.

---

## LLM-Agent-Specific Tuning Notes

The slow path is often outside the JVM:

```text
Ollama queueing
model inference
GPU memory
CPU saturation
prompt length
context length
tool latency
network latency
JSON parsing
```

Separate benchmarks into:

```text
fake LLM runtime benchmark
real Ollama benchmark
real external LLM benchmark
```

Do not tune the JVM using only real Ollama results. You may be measuring model throughput, not JVM behavior.

---

## Experiment Template

Copy this for every tuning pass.

```md
# JVM Tuning Experiment

## Date

## Service / Commit

## JDK

## Environment

- Local / Docker / Kubernetes:
- CPU limit:
- Memory limit:
- Model:
- LLM mode: fake / Ollama / external

## Workload

- Concurrent workflows:
- Requests/sec:
- Tenant mix:
- Duration:
- Prompt size:
- Expected behavior:

## Baseline Flags

```bash

```

## Baseline Observations

- p50:
- p95:
- p99:
- Throughput:
- Heap:
- GC:
- CPU:
- Threads:
- Timeouts:
- Errors:

## Hypothesis

## Change

```bash

```

## Result

- p50:
- p95:
- p99:
- Throughput:
- Heap:
- GC:
- CPU:
- Threads:
- Timeouts:
- Errors:

## Decision

- Keep / Revert / Retest:

## Notes

```

---

## Flag Recipes

### Recipe 1 — Safe Baseline

```bash
-Xms512m
-Xmx2g
-Xlog:gc*,safepoint:file=logs/gc.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=jfr/baseline.jfr,dumponexit=true,settings=profile
```

Use for local testing.

---

### Recipe 2 — Container Baseline

```bash
-XX:InitialRAMPercentage=40
-XX:MaxRAMPercentage=70
-Xlog:gc*,safepoint:file=/app/logs/gc.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=/app/jfr/baseline.jfr,dumponexit=true,settings=default
```

Use for Docker/Kubernetes.

---

### Recipe 3 — Low-Latency GC Trial

```bash
-XX:+UseZGC
-XX:InitialRAMPercentage=40
-XX:MaxRAMPercentage=70
-Xlog:gc*,safepoint:file=/app/logs/gc-zgc.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=/app/jfr/zgc-trial.jfr,dumponexit=true,settings=profile
```

Use only after GC pauses are proven to be the latency problem.

---

### Recipe 4 — Heap Fixed-Size Trial

```bash
-Xms2g
-Xmx2g
-Xlog:gc*,safepoint:file=logs/gc-fixed-heap.log:time,uptime,level,tags
-XX:StartFlightRecording=filename=jfr/fixed-heap.jfr,dumponexit=true,settings=profile
```

Use when heap resizing or unstable memory behavior is suspected.

---

### Recipe 5 — JFR Spike Capture

```bash
jcmd <PID> JFR.start name=spike settings=profile duration=120s filename=jfr/spike.jfr
```

Use during a known bad event.

---

## Benchmarking Procedure

Run these in order:

```text
1. Fake LLM, low concurrency
2. Fake LLM, high concurrency
3. Fake LLM, tenant flood
4. Real Ollama, low concurrency
5. Real Ollama, controlled concurrency
6. Soak test
```

Capture for each run:

```text
JFR
GC log
application metrics
load-generator output
container CPU/memory stats
Ollama metrics if available
```

Repeat at least 3 times before trusting a result.

---

## JVM Tuning Decision Tree

### Is p99 high?

```text
Check JFR:
  - GC pause?
  - blocked thread?
  - slow socket read?
  - lock contention?
  - CPU saturation?
```

If LLM/network dominates, tune LLM/client/concurrency first.

If GC dominates, inspect allocation and heap.

If threads are blocked, fix blocking isolation.

---

### Is memory growing?

```text
Check:
  - actor count
  - mailbox depth
  - workflow state
  - retained prompts
  - conversation history
  - caches
  - thread count
```

If growth is unbounded, fix lifecycle/quotas before increasing heap.

---

### Are GC pauses large?

```text
Check:
  - heap size
  - allocation rate
  - humongous objects
  - old-gen pressure
  - safepoints
```

Then trial:

```text
larger heap
reduced allocation
ZGC
G1 tuning only if needed
```

---

### Are actor dispatchers blocked?

```text
Fix design.
Do not tune JVM flags first.
```

---

## Explicit DO NOT DO THIS

```text
Do not copy random JVM flags from blog posts.
Do not change 10 flags at once.
Do not tune without a reproducible workload.
Do not rely on average latency.
Do not ignore p95/p99/p999.
Do not run LLM calls on actor dispatcher threads.
Do not solve unbounded queues with a bigger heap.
Do not set heap equal to container memory.
Do not benchmark only with real Ollama.
Do not leave high-overhead profiling settings on forever.
Do not ignore disk usage from continuous JFR recordings.
Do not expose JFR files publicly; they may contain sensitive runtime data.
Do not assume JVM flags fix poor actor topology.
```

---

## Minimal First Tuning Pass for the Pekko Agent Runtime

Run:

```bash
java \
  -Xms512m \
  -Xmx2g \
  -Xlog:gc*,safepoint:file=logs/gc-baseline.log:time,uptime,level,tags \
  -XX:StartFlightRecording=filename=jfr/baseline.jfr,dumponexit=true,settings=profile \
  -jar build/libs/pekko-agent-runtime.jar
```

Test:

```text
100 fake LLM workflows
500 fake LLM workflows
1000 fake LLM workflows
10 real Ollama workflows
tenant flood test
30-minute soak test
```

Inspect:

```text
actor dispatcher blocked time
allocation rate
GC pauses
thread states
socket I/O
timeouts
mailbox growth
heap after warmup
heap after sustained load
```

Only then decide whether JVM flags are worth changing.

---

## Recommended Defaults for This Project

For MVP:

```text
Java 21+
G1GC default
JFR on demand
GC logging enabled in tests
bounded mailboxes
no blocking on actor dispatchers
fake LLM benchmark before real Ollama benchmark
```

For later staging:

```text
continuous low-overhead JFR
Prometheus/Grafana
OpenTelemetry traces
per-tenant metrics
container memory tuning
GC comparison: G1 vs ZGC
```

---

## Source References

These are useful official references for this skill:

- Oracle Java command reference: JVM options, GC options, logging options
- Oracle Java troubleshooting guide: `jcmd`, JFR diagnostics
- Oracle JFR / JDK Mission Control documentation
- OpenJDK JDK Mission Control overview
- Oracle G1 GC tuning guide
- Oracle ZGC tuning guide
- OpenJDK JEP 349: JFR Event Streaming
