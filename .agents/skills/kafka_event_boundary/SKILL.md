---
name: Kafka Event Boundary
description: Define implementation of Kafka around Pekko-based runtime
---

# Skill: Kafka Event Boundary for Pekko Agent Runtime

## Purpose

Use this skill **after the initial Pekko + LangChain4j + Ollama MVP** is working.

This skill defines how to introduce Apache Kafka around a Pekko-based LLM agent runtime as a durable event and integration boundary.

Kafka should be used for:

- durable workflow events
- audit logs
- tenant activity streams
- usage and billing events
- asynchronous ingestion
- integration with external systems
- replayable event history
- projections/read models
- analytics pipelines
- long-running job handoff

Kafka should **not** be used as the default internal message bus between Pekko actors.

---

## Core Rule

```text
Pekko actor messages:
  inside the runtime

gRPC:
  service-to-service request/response boundary

Kafka:
  durable event/integration/replay boundary
```

For internal agent orchestration, keep using Pekko Typed messages.

Good:

```text
PlannerAgent -> ResearchAgent -> ExecutorAgent
```

Bad:

```text
PlannerAgent -> Kafka -> ResearchAgent
```

Kafka adds durability, replay, and integration value, but also adds latency, serialization, offset management, ordering constraints, duplicate handling, and operational complexity.

---

## When to Add Kafka

Do not add Kafka in the first MVP.

Add Kafka when you need at least one of these:

```text
auditability
event replay
cross-service integration
analytics
billing
external workflow consumers
durable ingestion
long-running asynchronous jobs
read-model projections
multi-service event coordination
```

Do not add Kafka merely because the system uses agents.

---

## Architecture Position

### MVP Without Kafka

```text
CLI / HTTP / gRPC
  ↓
GatewayActor
  ↓
TenantSupervisor
  ↓
ConversationActor
  ↓
PlannerAgent
  ↓
ResearchAgent
  ↓
ExecutorAgent
  ↓
LangChain4j / Ollama
```

### Post-MVP With Kafka

```text
CLI / HTTP / gRPC
  ↓
GatewayActor
  ↓
TenantSupervisor
  ↓
ConversationActor
  ↓
PlannerAgent / ResearchAgent / ExecutorAgent
  ↓
WorkflowEventPublisher
  ↓
Kafka
  ↓
Audit / Billing / Analytics / Projections / External Consumers
```

### Kafka as Ingestion Boundary

```text
Kafka topic: document.ingested
  ↓
Pekko Kafka Consumer Stream
  ↓
IngestionCoordinatorActor
  ↓
ChunkingAgent / EmbeddingAgent / MemoryWriterAgent
```

### Kafka as Projection Boundary

```text
Kafka topic: workflow.events
  ↓
Pekko Projection
  ↓
Read model / reporting DB / search index
```

---

## Recommended First Kafka Use Cases

Add Kafka in this order.

### 1. Workflow Event Publishing

Publish events from the agent runtime:

```text
WorkflowStarted
PlanCreated
TaskStarted
TaskCompleted
TaskFailed
ToolCallStarted
ToolCallCompleted
ToolCallFailed
WorkflowCompleted
WorkflowFailed
HumanApprovalRequested
TenantUsageRecorded
```

This is append-only telemetry/audit. It is the safest first use case.

---

### 2. Usage and Billing Events

Publish tenant usage:

```text
tenantId
workflowId
modelName
promptTokens
completionTokens
toolCalls
durationMs
startedAt
completedAt
status
```

This lets billing/analytics evolve independently from the runtime.

---

### 3. Document or Job Ingestion

Consume work requests from Kafka:

```text
DocumentIngestionRequested
BatchEvaluationRequested
MemoryRefreshRequested
ReportGenerationRequested
```

This is useful when requests can be asynchronous and durable.

---

### 4. Read-Model Projections

Use Kafka events to build queryable state:

```text
tenant workflow dashboard
conversation history index
task status table
audit search
billing rollups
```

Use Pekko Projection when the read model must track offsets and resume safely.

---

## Recommended Project Structure

Add Kafka as an infrastructure boundary.

```text
pekko-agent-runtime/
│
├── app/
│   └── Main.java
│
├── agents/
│   ├── planner/
│   ├── research/
│   ├── executor/
│   └── shared/
│
├── protocols/
│   ├── AgentCommands.java
│   ├── WorkflowEvents.java
│   └── TenantEvents.java
│
├── kafka/
│   ├── KafkaEventPublisher.java
│   ├── KafkaEventConsumer.java
│   ├── KafkaTopics.java
│   ├── KafkaSerialization.java
│   ├── KafkaEventEnvelope.java
│   ├── WorkflowEventProducerStream.java
│   ├── IngestionConsumerStream.java
│   └── DeadLetterPublisher.java
│
├── projections/
│   ├── WorkflowProjection.java
│   ├── TenantUsageProjection.java
│   └── ProjectionHandlers.java
│
├── infrastructure/
│   ├── supervision/
│   ├── telemetry/
│   ├── serialization/
│   └── config/
│
└── docker/
    └── docker-compose.kafka.yml
```

---

## Dependencies

Use the current versions from the Apache Pekko documentation when implementing.

Typical modules:

```gradle
implementation("org.apache.pekko:pekko-connectors-kafka_2.13:<version>")
implementation("org.apache.pekko:pekko-stream_2.13:<version>")
implementation("org.apache.pekko:pekko-actor-typed_2.13:<version>")
```

If using Pekko Projection with Kafka:

```gradle
implementation("org.apache.pekko:pekko-projection-kafka_2.13:<version>")
implementation("org.apache.pekko:pekko-projection-jdbc_2.13:<version>")
```

Use the Scala binary version that matches your Pekko dependency line, commonly:

```text
2.13
```

Even in Java projects, Pekko artifacts are published with Scala binary suffixes.

---

## Topic Design

Start with a small topic set.

```text
agent.workflow.events.v1
agent.tenant-usage.events.v1
agent.ingestion.commands.v1
agent.dead-letter.v1
```

Avoid creating one topic per event type at first.

Prefer:

```text
one domain topic
many event types
versioned envelope
```

Example:

```text
agent.workflow.events.v1
  - WorkflowStarted
  - PlanCreated
  - TaskStarted
  - TaskCompleted
  - WorkflowCompleted
  - WorkflowFailed
```

---

## Event Envelope

Every Kafka event should use a consistent envelope.

```json
{
  "eventId": "uuid",
  "eventType": "TaskCompleted",
  "eventVersion": 1,
  "occurredAt": "2026-05-28T12:00:00Z",
  "tenantId": "tenant-123",
  "workflowId": "workflow-456",
  "conversationId": "conversation-789",
  "correlationId": "request-abc",
  "causationId": "event-def",
  "source": "pekko-agent-runtime",
  "schema": "agent.workflow.TaskCompleted.v1",
  "payload": {}
}
```

Required fields:

```text
eventId
eventType
eventVersion
occurredAt
tenantId
correlationId
source
payload
```

Recommended fields:

```text
workflowId
conversationId
agentId
causationId
traceId
spanId
modelName
nodeId
```

---

## Event Key Strategy

Kafka keys determine partitioning and ordering.

Use:

```text
tenantId
workflowId
conversationId
```

Depending on ordering needs.

### Key by workflowId

Good for:

```text
ordered workflow lifecycle events
single workflow status reconstruction
task progression
```

```text
key = workflowId
```

### Key by tenantId

Good for:

```text
tenant-level usage streams
billing rollups
tenant isolation
```

```text
key = tenantId
```

### Key by conversationId

Good for:

```text
conversation replay
agent memory/audit reconstruction
```

```text
key = conversationId
```

Do not use random keys if ordering matters.

---

## Delivery Semantics

Default assumption:

```text
at-least-once delivery
```

This means consumers must be idempotent.

Kafka and Pekko Connectors Kafka can support stronger patterns, but your first design should assume duplicates are possible.

### Producer Rule

A producer may publish an event more than once during retries or failure recovery.

Therefore:

```text
eventId must be unique
event consumers must deduplicate where necessary
side effects must be idempotent
```

### Consumer Rule

A consumer should process an event before committing the offset.

Commit offsets only after the side effect is complete.

Bad:

```text
commit offset
then update database
```

Good:

```text
update database idempotently
then commit offset
```

---

## Idempotency Rules

Every consumer that performs side effects needs an idempotency strategy.

Options:

```text
deduplicate by eventId
upsert by workflowId + taskId
insert with unique constraint
store processed_event table
use idempotent external APIs
make writes deterministic
```

Example table:

```sql
CREATE TABLE processed_events (
  event_id VARCHAR PRIMARY KEY,
  processed_at TIMESTAMP NOT NULL
);
```

Process flow:

```text
receive event
start transaction
check processed_events
apply side effect
insert eventId
commit transaction
commit Kafka offset
```

---

## Offset Management

Offset strategy depends on the consumer type.

### Simple Kafka Consumer Stream

Use Pekko Connectors Kafka committable source when consuming events directly.

```text
consume message
process message
commit offset
```

Be careful not to commit too early.

---

### Projection Consumer

Use Pekko Projection when you are building a read model or processing a durable stream into a database.

Projection benefits:

```text
offset tracking
restart/resume support
handler abstraction
integration with JDBC/Slick offset stores
good fit for CQRS/read models
```

Use projection when the consumer has durable state.

---

## Dead Letter Topic

Create a dead-letter topic early.

```text
agent.dead-letter.v1
```

Dead-letter event envelope:

```json
{
  "eventId": "uuid",
  "sourceTopic": "agent.workflow.events.v1",
  "sourcePartition": 0,
  "sourceOffset": 12345,
  "failureType": "DeserializationError",
  "failureMessage": "Could not parse event",
  "failedAt": "2026-05-28T12:05:00Z",
  "originalKey": "workflow-456",
  "originalValue": {}
}
```

Send to DLQ when:

```text
event cannot be deserialized
event schema is unsupported
required fields are missing
consumer exhausts retries
processing is permanently invalid
```

Do not use DLQ for normal temporary failures. Retry first.

---

## Retry Strategy

Use bounded retries.

```text
immediate retry: 1-2 times
short backoff retry: seconds
long retry / retry topic: optional later
dead-letter: after permanent failure or retry exhaustion
```

Do not retry forever inside a stream stage.

Retry storms can take down the runtime.

---

## Schema Strategy

Start simple but version everything.

Recommended early choice:

```text
JSON envelope + JSON payload
```

Later options:

```text
JSON Schema
Avro + Schema Registry
Protocol Buffers
```

Rules:

```text
never remove fields from existing event versions
only add optional fields to existing versions
create v2 for breaking changes
include eventVersion
include schema name
test backward compatibility
```

Topic names may include version:

```text
agent.workflow.events.v1
```

Event types also include version:

```text
TaskCompleted.v1
```

---

## Producer Patterns

### Pattern 1: Actor Publishes Event to Kafka Publisher Actor

```text
WorkflowActor
  -> KafkaEventPublisherActor
  -> Pekko Stream Producer
  -> Kafka
```

Use this when actors need to emit events without knowing Kafka details.

Actor sends:

```java
public record PublishEvent(KafkaEventEnvelope event) {}
```

Publisher handles:

```text
serialization
topic routing
producer flow
failure handling
metrics
```

---

### Pattern 2: Stream-Based Event Publishing

```text
Source.queue()
  -> serialization flow
  -> Producer.flexiFlow()
  -> metrics
```

Use this when many events are produced and backpressure matters.

---

### Pattern 3: Outbox Pattern

Use later when event publication must be transactional with database writes.

```text
actor/state update
  -> write DB row + outbox row in same transaction
  -> outbox publisher reads row
  -> publishes to Kafka
  -> marks row published
```

Use this for high-integrity enterprise workflows.

---

## Consumer Patterns

### Pattern 1: Kafka Command Consumer to Actor

```text
Kafka topic: agent.ingestion.commands.v1
  -> Pekko Kafka Consumer Stream
  -> validate command
  -> send command to IngestionCoordinatorActor
```

Use when Kafka triggers work in Pekko.

Important:

```text
do not commit offset until the actor has accepted or completed the work
define what "accepted" means
use idempotent command IDs
```

---

### Pattern 2: Kafka Event Projection

```text
Kafka topic: agent.workflow.events.v1
  -> Pekko Projection
  -> WorkflowProjectionHandler
  -> read model DB
```

Use when building query state.

---

### Pattern 3: External Consumer

```text
Kafka topic
  -> billing service
  -> analytics service
  -> audit service
```

Use when the Pekko runtime should not own downstream concerns.

---

## Backpressure

Pekko Connectors Kafka is stream-oriented and built on Pekko Streams, so use stream backpressure intentionally.

Rules:

```text
bound buffers
bound parallelism
watch consumer lag
watch mailbox depth
watch producer queue time
watch commit latency
```

Do not bridge Kafka into actors with unbounded queues.

Bad:

```text
Kafka consumer reads as fast as possible
sends unlimited messages to actor mailbox
```

Good:

```text
Kafka stream controls parallelism
actor ask/ack controls acceptance
offset commits happen after acceptance or completion
```

---

## Metrics

Minimum producer metrics:

```text
events published/sec
publish latency p50/p95/p99
publish failures
serialization failures
producer buffer pressure
topic/partition distribution
```

Minimum consumer metrics:

```text
records consumed/sec
consumer lag
processing latency
commit latency
retry count
DLQ count
deserialization failures
handler failures
```

Agent-runtime correlation labels:

```text
tenantId
workflowId
conversationId
agentType
eventType
topic
partition
nodeId
```

---

## Security

Before production, define:

```text
TLS
SASL mechanism
ACLs
topic-level permissions
secret management
PII redaction
event retention policy
encryption at rest
audit access
```

Never publish raw prompts or model outputs to Kafka by default.

Use event summaries, metadata, hashes, or redacted payloads unless full content retention is explicitly required.

---

## Retention and Compaction

For audit/event history:

```text
cleanup.policy=delete
retention.ms=<policy-defined>
```

For current state by key:

```text
cleanup.policy=compact
```

Use compacted topics for:

```text
tenant configuration snapshots
workflow latest status
agent registry state
```

Use delete-retention topics for:

```text
workflow lifecycle events
tool call events
usage events
audit trails
```

Do not mix audit history and current-state semantics casually in one topic.

---

## Testing Strategy

### Unit Tests

Test:

```text
event envelope creation
serialization/deserialization
schema validation
topic routing
key selection
idempotency logic
DLQ formatting
```

### Integration Tests

Use a local Kafka container.

Test:

```text
producer publishes valid events
consumer processes and commits
consumer resumes after restart
duplicate events are idempotent
bad events go to DLQ
offset is not committed before processing
consumer lag is visible
```

### Failure Tests

Inject:

```text
Kafka unavailable
broker restart
serialization error
consumer crash before commit
consumer crash after side effect before commit
duplicate delivery
slow downstream database
DLQ topic unavailable
```

### Load Tests

Measure:

```text
events/sec
producer latency
consumer lag
offset commit latency
Pekko mailbox pressure
heap usage
GC pressure
tenant-level impact
```

---

## First Implementation Plan After MVP

### Step 1: Add Event Types

Create:

```text
WorkflowEvents.java
KafkaEventEnvelope.java
KafkaTopics.java
```

Implement:

```text
WorkflowStarted
PlanCreated
TaskStarted
TaskCompleted
TaskFailed
WorkflowCompleted
WorkflowFailed
TenantUsageRecorded
```

---

### Step 2: Add Kafka Publisher Boundary

Create:

```text
KafkaEventPublisher
KafkaEventPublisherActor
WorkflowEventProducerStream
```

Actors publish events by sending messages to the publisher actor.

---

### Step 3: Add Docker Compose Kafka

Create:

```text
docker-compose.kafka.yml
```

Use it only for local integration tests.

---

### Step 4: Add Integration Tests

Test:

```text
publish workflow event
read from topic
validate envelope
verify event key
verify event type
```

---

### Step 5: Add Usage/Billing Consumer

Create a simple consumer or projection:

```text
agent.tenant-usage.events.v1
  -> TenantUsageProjection
  -> local Postgres table
```

---

### Step 6: Add DLQ

Add:

```text
agent.dead-letter.v1
```

Test malformed events.

---

### Step 7: Add Projection

Use Pekko Projection for one durable read model:

```text
WorkflowStatusProjection
```

---

## Local Docker Compose Sketch

Use this as a starting point. Adjust images and settings for your local environment.

```yaml
services:
  kafka:
    image: apache/kafka:latest
    container_name: local-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
```

For team environments, prefer a more production-like Kafka setup and explicit topic creation.

---

## Configuration Sketch

```hocon
agent-runtime.kafka {
  bootstrap-servers = "localhost:9092"

  topics {
    workflow-events = "agent.workflow.events.v1"
    tenant-usage-events = "agent.tenant-usage.events.v1"
    ingestion-commands = "agent.ingestion.commands.v1"
    dead-letter = "agent.dead-letter.v1"
  }

  producer {
    client-id = "pekko-agent-runtime"
    acks = "all"
    enable-idempotence = true
  }

  consumer {
    group-id = "pekko-agent-runtime"
    auto-offset-reset = "earliest"
    enable-auto-commit = false
  }
}
```

---

## Best Practices

```text
Use Kafka at the boundary, not between internal agents.
Keep actor protocols separate from Kafka event contracts.
Use event envelopes.
Use stable event IDs.
Use deterministic event keys.
Assume at-least-once delivery.
Make consumers idempotent.
Commit offsets after processing.
Use bounded retries.
Create DLQ early.
Track consumer lag.
Label metrics by tenantId and eventType.
Version topics and schemas.
Do not publish raw sensitive prompt data by default.
```

---

## Explicit DO NOT DO THIS

```text
Do not replace Pekko actor messaging with Kafka for internal agent calls.
Do not use Kafka for request/response between local actors.
Do not commit offsets before side effects are complete.
Do not assume exactly-once behavior removes the need for idempotency.
Do not create one topic per tenant for the MVP.
Do not create one topic per event type too early.
Do not publish raw prompts, secrets, credentials, or full model outputs by default.
Do not use random Kafka keys when ordering matters.
Do not let Kafka consumers flood actor mailboxes.
Do not retry forever.
Do not ignore poison messages.
Do not skip DLQ design.
Do not deploy without consumer lag metrics.
Do not mix command topics and event topics without clear naming.
Do not couple external Kafka schema directly to internal actor classes.
```

---

## Kafka vs Pekko Cluster Sharding

Use Pekko Cluster Sharding for:

```text
routing messages to entity actors
tenant/session/workflow ownership
stateful actor placement
internal orchestration
elastic cluster distribution
```

Use Kafka for:

```text
durable event log
integration with other systems
event replay
analytics
audit
external consumers
asynchronous command ingestion
```

They are complementary.

---

## Kafka vs gRPC

Use gRPC for:

```text
external synchronous request/response
streaming API to clients
tool service boundary
runtime control plane
```

Use Kafka for:

```text
asynchronous events
durability
replay
fan-out
integration
```

Do not use Kafka where the caller needs an immediate answer.

---

## Kafka Skill Completion Criteria

Kafka integration is ready when:

```text
workflow events are published with stable envelopes
event keys preserve required ordering
consumer can restart and resume
consumer is idempotent
DLQ works for bad events
consumer lag is observable
offset commits happen after processing
tenantId is present in metrics/events
tests cover duplicate delivery
tests cover crash before/after commit
no internal agent-to-agent traffic uses Kafka unnecessarily
```

---

## References to Check During Implementation

Use the current official documentation when implementing:

- Apache Pekko Connectors Kafka documentation
- Apache Pekko Connectors Kafka at-least-once delivery docs
- Apache Pekko Projection Kafka docs
- Apache Kafka official documentation
- Kafka client producer/consumer configuration docs
- Your organization’s security, retention, and compliance policies
