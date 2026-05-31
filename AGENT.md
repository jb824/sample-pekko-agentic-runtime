# AGENT.md — Working Memory for Pekko LLM Agent Runtime

Purpose: current project state for Codex/agent harnesses. Keep this file factual, short-lived, and implementation-specific.

- Durable procedures and patterns belong in `.agents/skills/**/SKILL.md`.
- This file is working memory: current objective, repo shape, decisions, known issues, and next steps.
- When implementation state changes, update this file in the same PR/commit.

---

## 1) Current Objective

Implement Google-source ingestion into the existing event-driven agent runtime.

Near-term sources:

1. **Google Business Profile (GBP)** reviews/updates
2. **YouTube** comments/engagement updates

Current target pipeline:

```text
Google source/mock source
  -> Quarkus ingest adapter
  -> Kafka topic: agent.commands.v1
  -> Pekko runtime
  -> Kafka topic: agent.workflow.events.v1
  -> Quarkus projection
  -> Cassandra/UI
```

For the first pass, prefer **mock Google events** over real Google account integration. Do not create fake/dummy Google Business Profiles for testing. Move to real OAuth/GBP only with a real eligible profile or approved client account.

---

## 2) Current System Snapshot

Runtime stack:

- Java 21
- Gradle for Pekko app
- Maven wrapper for `quarkus-adapter`
- Pekko Typed actor runtime
- LangChain4j LLM harness
- LLM backends: Ollama default, vLLM, vLLM gRPC
- Kafka as durable event boundary
- Quarkus adapter for ingestion, projection, and UI
- Cassandra for current summary/read-model storage

Current Pekko workflows:

- `research`
- `planner-executor`
- `react`

Current tools exposed to workflows:

- `time.now`
- `web.search`
- `arxiv.search`
- `pubmed.search`

Current source adapters already present:

- Guardian webhook/poller
- Hacker News poller

---

## 3) Boundary Ownership

Quarkus adapter owns:

- external API auth/OAuth/secrets handling
- Google token storage/refresh
- webhooks, Pub/Sub subscribers, and pollers
- source payload validation and normalization
- publication to Kafka input topic
- output projection persistence
- UI/API endpoints

Pekko runtime owns:

- workflow orchestration
- ReAct/planner/research execution
- tool invocation through tool actors
- bounded LLM reasoning steps
- internal actor lifecycle/supervision
- output event emission

Kafka owns:

- durable inbound commands/events
- durable workflow output events
- replay/integration boundary

Cassandra owns:

- queryable read model for UI/API

Do not move Google-specific API clients into Pekko workflow actors.

---

## 4) Layering Constraint

For tools in the `app` module:

```text
workflow/agent actor
  -> tool actor
  -> ToolService
  -> adapter/API client
```

Rules:

- Agents/workflows do not call external APIs directly.
- Tool actors stay thin.
- Adapter implementations are injected through `ToolWiring`.
- Long-running or synchronous calls must not block the default actor dispatcher.

---

## 5) Google Dependency Direction

Use generated Google API clients for YouTube/GBP APIs, modern Google Auth for credentials, and Google Cloud libraries only for Cloud infrastructure such as Pub/Sub.

### Maven dependency direction

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.google.cloud</groupId>
      <artifactId>libraries-bom</artifactId>
      <version>26.83.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- YouTube Data API generated client -->
  <dependency>
    <groupId>com.google.apis</groupId>
    <artifactId>google-api-services-youtube</artifactId>
    <version>v3-rev20260525-2.0.0</version>
  </dependency>

  <!-- Google Business Profile generated clients -->
  <dependency>
    <groupId>com.google.apis</groupId>
    <artifactId>google-api-services-mybusinessaccountmanagement</artifactId>
    <version>v1-rev20260512-2.0.0</version>
  </dependency>

  <dependency>
    <groupId>com.google.apis</groupId>
    <artifactId>google-api-services-mybusinessbusinessinformation</artifactId>
    <version>v1-rev20260426-2.0.0</version>
  </dependency>

  <!-- Generated Google API client plumbing -->
  <dependency>
    <groupId>com.google.api-client</groupId>
    <artifactId>google-api-client</artifactId>
    <version>2.9.0</version>
  </dependency>

  <!-- Preferred modern Google auth library -->
  <dependency>
    <groupId>com.google.auth</groupId>
    <artifactId>google-auth-library-oauth2-http</artifactId>
    <version>1.47.0</version>
  </dependency>

  <!-- Google Cloud Pub/Sub for GBP notifications -->
  <dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-pubsub</artifactId>
  </dependency>
</dependencies>
```

Notes:

- Do not add `google-api-client-appengine` unless deploying to an App Engine-specific runtime that requires it.
- Re-check generated API client versions before committing dependency updates.
- For YouTube, start with comment polling via `commentThreads.list` unless a better push source is intentionally added.
- For GBP reviews, Pub/Sub notifications can signal new/updated reviews, but the adapter should hydrate full review/resource data before publishing a normalized internal event.

---

## 6) Canonical Inbound Event Contract

All source adapters must map into one Pekko entry contract. Do not create separate Pekko-bound event formats per source.

Minimum fields:

```json
{
  "eventId": "stable-id",
  "eventType": "SourceItemReceived",
  "eventVersion": 1,
  "tenantId": "tenant-id",
  "source": "guardian|hackernews|google-business-profile|youtube|mock-google",
  "sourceRecordId": "source-native-record-id",
  "occurredAt": "ISO-8601 timestamp",
  "payload": {}
}
```

Required source-specific stable IDs:

- Guardian: article/content ID or URL hash
- Hacker News: item ID
- GBP: account ID + location ID + review/resource ID
- YouTube: channel/video/comment/commentThread ID

Target delivery semantics:

- at-least-once ingest
- idempotent handling by `eventId` + source record IDs
- no silent failure swallowing

---

## 7) Google Iteration Scope

### 7.1 Phase 0 — Mock-first integration

Implement mock Google inbound events first:

- mock GBP review received/updated
- mock YouTube comment received/updated
- publish to `agent.commands.v1`
- verify Pekko workflow execution
- verify projection to Cassandra/UI

### 7.2 Phase 1 — Google auth foundation

Implement in Quarkus:

- OAuth config and redirect/callback flow or admin-provided refresh-token path
- credential storage abstraction
- token refresh using `google-auth-library-oauth2-http`
- no token logging
- no checked-in credentials

### 7.3 Phase 2 — GBP ingest

Implement in Quarkus:

- account/location discovery
- review listing/hydration
- optional Pub/Sub notification receiver/subscriber
- normalized internal event publisher

Important constraint: real GBP testing requires a real eligible Business Profile or approved client account. Do not create dummy profiles.

### 7.4 Phase 3 — YouTube ingest

Implement in Quarkus:

- channel/video scope discovery
- comment/comment-thread polling
- watermarking by time/page token/source ID
- normalized internal event publisher

### 7.5 Phase 4 — Projection/UI visibility

Extend UI/API with:

- source type
- source record ID
- processing status
- workflow ID
- generated output
- failure reason if present

---

## 8) Kafka Topics

Current topics:

```text
agent.commands.v1
agent.workflow.events.v1
```

Possible later topics:

```text
agent.dead-letter.v1
agent.tenant-usage.events.v1
agent.memory-updates.v1
```

Rules:

- Kafka is the durable boundary, not internal actor messaging.
- Pekko internal agent-to-agent communication remains typed actor messages.
- Producers: Quarkus ingest adapter, Pekko output publisher, test publishers.
- Consumers: Pekko Kafka runtime, Quarkus projection service, future audit/billing/memory consumers.

---

## 9) Known Runtime Issue To Watch

Observed/likely issue:

```text
ToolTimeout dead letters after ReActWorkflowActor termination
```

Interpretation:

- A workflow actor likely completed/stopped while a tool timeout message was still scheduled.

Preferred fix:

- Use `Behaviors.withTimers` for actor-owned timers.
- Use unique timer keys per tool call.
- Cancel timer when tool result arrives.
- Ignore stale timeout/result messages defensively.
- Stop workflow only after required output publishing/cleanup is done.

Do not silence dead-letter logging until lifecycle behavior is understood.

---

## 10) Local Runtime Requirements

Expected local services:

- Java 21+
- Kafka on `localhost:9092`
- Cassandra on `localhost:9042`
- Optional Ollama at `http://localhost:11434`
- Optional vLLM at `http://localhost:8000/v1`

Quarkus UI/API:

```text
http://localhost:8081
```

---

## 11) Current Project Structure

```text
app/
  src/main/java/com/example/agent/
    Main.java
    config/
    gateway/
    kafka/
    llm/
    prompts/
    protocol/
    tool/
      adapter/
      service/
      ToolRegistryActor.java
      ToolWiring.java
      DefaultToolWiring.java
      ServiceBackedToolWiring.java
    workflow/

quarkus-adapter/
  cassandra/schema.cql
  src/main/java/com/example/adapter/
    inbound/
      CanonicalInboundEvent.java
      CommandEventPublisher.java
      GuardianWebhookResource.java
      GuardianPoller.java
      HackerNewsPoller.java
      InboundEventDeduplicator.java
      MockGoogleInboundResource.java
      MockGoogleBusinessProfileReviewRequest.java
      MockYouTubeCommentRequest.java
      GoogleBusinessProfilePayload.java
      YouTubeCommentPayload.java
      GoogleCredentialsProvider.java
      GoogleBusinessProfilePoller.java
      YouTubePoller.java
    outbound/
      NewsSummaryConsumer.java
      CassandraSummaryWriter.java
      SummaryQueryResource.java
    ui/
      UiResource.java
  src/main/resources/
    application.properties
    templates/UiResource/
      index.html
      summaryRows.html
      webhookResult.html
```

Later Google service split:

```text
quarkus-adapter/src/main/java/com/example/adapter/inbound/google/
  GoogleOAuthResource.java
  GoogleCredentialStore.java
  GoogleCredentialService.java
  GoogleBusinessProfileIngestService.java
  GoogleBusinessProfilePoller.java
  GoogleBusinessProfilePubSubConsumer.java
  YouTubeIngestService.java
  YouTubeCommentPoller.java
  GoogleInboundEventMapper.java
```

---

## 12) Local Commands

Pekko runtime:

```bash
./gradlew compileJava
./gradlew run
```

Pekko in Kafka mode:

```bash
APP_MODE=kafka-runtime \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
KAFKA_INPUT_TOPIC=agent.commands.v1 \
KAFKA_OUTPUT_TOPIC=agent.workflow.events.v1 \
KAFKA_GROUP_ID=pekko-agent-runtime \
AGENT_WORKFLOW=react \
AGENT_TOOLS=time.now,web.search,arxiv.search,pubmed.search \
./gradlew run
```

Quarkus adapter tests:

```bash
./quarkus-adapter/mvnw -f quarkus-adapter/pom.xml clean test
```

Quarkus dev mode:

```bash
cd quarkus-adapter && ./mvnw clean quarkus:dev
```

Quarkus mock-only dev mode:

```bash
cd quarkus-adapter
GOOGLE_GBP_POLL_ENABLED=false \
GOOGLE_YOUTUBE_POLL_ENABLED=false \
GUARDIAN_POLL_ENABLED=false \
HACKERNEWS_POLL_ENABLED=false \
./mvnw quarkus:dev
```

Mock Google publish examples:

```bash
curl -X POST http://localhost:8081/mock/google/gbp-review \
  -H "content-type: application/json" \
  -d '{"tenantId":"tenant-default","accountId":"accounts/mock-account-1","locationId":"locations/mock-location-1","reviewId":"reviews/mock-review-1","reviewerDisplayName":"Mock Reviewer","starRating":"FIVE","comment":"The team responded quickly and clearly.","updateTime":"2026-05-31T16:00:00Z"}'
```

```bash
curl -X POST http://localhost:8081/mock/google/youtube-comment \
  -H "content-type: application/json" \
  -d '{"tenantId":"tenant-default","channelId":"mock-channel-1","videoId":"mock-video-1","commentId":"mock-comment-1","authorDisplayName":"Mock Viewer","textDisplay":"Can you share more details about this?","publishedAt":"2026-05-31T16:00:00Z","likeCount":3}'
```

Cassandra schema load:

```bash
sudo docker exec -i cassandra-dev cqlsh < quarkus-adapter/cassandra/schema.cql
```

---

## 13) Risks To Actively Manage

- Blocking actor dispatchers with synchronous/long-running operations
- Contract drift between source adapters and canonical inbound event model
- Duplicate event processing without idempotency checks
- Token/credential leakage in logs, config, tests, or fixtures
- Unbounded prompt/context/tool-call growth in workflows
- Kafka publish/consume failures being swallowed
- Source-specific logic leaking into Pekko boundary
- Google API quota/rate-limit behavior during polling
- Real GBP API limitations/approval requirements blocking local test progress

---

## 14) Explicit DO NOTs

- Do not create one ActorSystem per request.
- Do not call external APIs directly from agent/workflow actors.
- Do not put Google clients inside Pekko workflows.
- Do not add source-specific event formats at the Pekko Kafka boundary.
- Do not swallow Kafka consume/publish failures silently.
- Do not hardcode tenant IDs.
- Do not commit secrets, tokens, API keys, OAuth client secrets, or refresh tokens.
- Do not create fake/dummy Google Business Profiles for testing.
- Do not use Kafka as internal agent-to-agent messaging.
- Do not disable dead-letter logs until lifecycle bugs are investigated.

---

## 15) Next Actions

Execution order:

1. Done: canonical inbound event type supports `source`, `sourceRecordId`, `tenantId`, and bounded `payload`.
2. Done: mock Google publisher endpoints exist for GBP review and YouTube comment events.
3. Next: run full local Kafka -> Pekko -> Kafka -> Cassandra/UI smoke test with mock GBP/YouTube events.
4. Done: ingest and projection idempotency safeguards are in place.
5. Done: Google dependency set is in `quarkus-adapter/pom.xml`.
6. Done: Google credential abstraction exists without real credentials in tests.
7. Next: split GBP review hydration/listing behind a dedicated service interface.
8. Next: split YouTube comment polling behind a dedicated service interface and add watermarking.
9. Partial: normalization/publisher tests exist; add broker-backed integration test when local test infrastructure is chosen.
10. Done: UI/API exposes source type, source record ID, and processing status.

---

## 16) Backlog / Not Current Iteration

Knowledge memory subsystem:

- event-centric knowledge/world model
- Postgres + pgvector or graph-backed memory
- article/document store
- entity/claim/event extraction
- memory retrieval pack before synthesis
- memory update events from completed workflows

Kafka extensions:

- dead-letter topic
- tenant usage events
- audit/billing projections

Runtime hardening:

- JFR + GC tuning workflow
- bounded mailbox/load tests
- tenant isolation tests
- ReAct workflow timer/dead-letter cleanup

---

## 17) Open Questions

- Google ingest mode per source: polling first, Pub/Sub where available, or both?
- Tenant routing model for Google assets: one tenant per Google account, many locations per tenant, or many tenants per account?
- Approval flow for generated GBP/YouTube replies: always human-approved, policy-driven, or tenant-configured?
- Where should credentials be stored locally and in staging?
- Should generated responses remain in Cassandra only, or also produce dedicated `agent.generated.outputs.v1` events later?
