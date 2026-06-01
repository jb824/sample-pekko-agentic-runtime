package com.example.agent.kafka;

import com.example.agent.config.AppConfig;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.tool.ToolRegistryActor;
import com.example.agent.tool.ToolWiring;
import com.example.agent.tool.DefaultToolWiring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.typesafe.config.ConfigFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.kafka.CommitterSettings;
import org.apache.pekko.kafka.ConsumerSettings;
import org.apache.pekko.kafka.Subscriptions;
import org.apache.pekko.kafka.javadsl.Committer;
import org.apache.pekko.kafka.javadsl.Consumer;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class KafkaRuntimeRunner {
    private KafkaRuntimeRunner() {
    }

    public static void run(AppConfig config) {
        ChatModel model = ChatModelFactory.create(config);
        ExecutorService llmExecutor = new ThreadPoolExecutor(
                config.llmThreads(),
                config.llmThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.llmQueueSize()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("llm-worker-" + UUID.randomUUID());
                    return thread;
                }
        );

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        KafkaProducer<String, String> outputProducer = new KafkaProducer<>(producerProps(config));
        Map<String, InboundCommandEvent> pending = new ConcurrentHashMap<>();

        Behavior<AgentResponse> root = Behaviors.setup(context -> {
            ActorRef<LlmProtocol.Command> llmWorker = context.spawn(
                    LlmWorkerActor.create(model, llmExecutor),
                    "llm-worker"
            );
            ActorRef<ToolProtocol.Command> toolRegistry = context.spawn(
                    ToolRegistryActor.create(createToolWiring(config)),
                    "tool-registry"
            );
            ActorRef<GatewayActor.Command> gateway = context.spawn(
                    GatewayActor.create(
                            llmWorker,
                            toolRegistry,
                            GatewayActor.WorkflowKind.fromConfig(config.workflowMode()),
                            config.enabledTools(),
                            config.maxTools(),
                            config.maxSteps(),
                            config.workflowTimeout(),
                            config.toolTimeout()
                    ),
                    "gateway"
            );

            startCommandConsumer(config, context.getSystem(), mapper, gateway, context.getSelf(), pending);

            return Behaviors.receive(AgentResponse.class)
                    .onMessage(AgentResponse.class, response -> {
                        InboundCommandEvent inbound = pending.remove(response.requestId());
                        if (inbound == null) {
                            context.getLog().warn("No pending source event found for request {}", response.requestId());
                            return Behaviors.same();
                        }
                        NewsSummaryGeneratedEvent outbound = NewsSummaryGeneratedEvent.from(inbound, response);
                        try {
                            String value = mapper.writeValueAsString(outbound);
                            outputProducer.send(new ProducerRecord<>(
                                    config.kafkaOutputTopic(),
                                    outbound.payload().articleId(),
                                    value
                            ));
                        } catch (Exception exception) {
                            context.getLog().error("Failed to publish workflow event for {}", response.requestId(), exception);
                        }
                        return Behaviors.same();
                    })
                    .build();
        });

        ActorSystem<AgentResponse> system = ActorSystem.create(root, "pekko-agent-kafka-runtime", ConfigFactory.load());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            outputProducer.flush();
            outputProducer.close();
            llmExecutor.shutdownNow();
        }));
        system.getWhenTerminated().toCompletableFuture().join();
    }

    private static void startCommandConsumer(
            AppConfig config,
            ActorSystem<?> system,
            ObjectMapper mapper,
            ActorRef<GatewayActor.Command> gateway,
            ActorRef<AgentResponse> replyTo,
            Map<String, InboundCommandEvent> pending
    ) {
        ConsumerSettings<String, String> settings = ConsumerSettings.create(system, new StringDeserializer(), new StringDeserializer())
                .withBootstrapServers(config.kafkaBootstrapServers())
                .withGroupId(config.kafkaGroupId())
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        CommitterSettings committerSettings = CommitterSettings.create(system);
        Materializer materializer = SystemMaterializer.get(system).materializer();
        AtomicInteger streamCounter = new AtomicInteger();
        system.log().info(
                "Kafka runtime consuming input_topic={} output_topic={} group_id={} workflow={} tools={}",
                config.kafkaInputTopic(),
                config.kafkaOutputTopic(),
                config.kafkaGroupId(),
                config.workflowMode(),
                config.enabledTools()
        );

        CompletionStage<Done> completion = Consumer.committableSource(settings, Subscriptions.topics(config.kafkaInputTopic()))
                .map(message -> {
                    ConsumerRecord<String, String> record = message.record();
                    try {
                        InboundCommandEvent event = mapper.readValue(record.value(), InboundCommandEvent.class);
                        String requestId = event.eventId();
                        pending.put(requestId, event);
                        String prompt = promptFor(event);
                        int received = streamCounter.incrementAndGet();
                        system.log().info(
                                "Kafka runtime received command count={} topic={} partition={} offset={} event_id={} event_type={} source={} source_record_id={}",
                                received,
                                record.topic(),
                                record.partition(),
                                record.offset(),
                                event.eventId(),
                                event.eventType(),
                                event.source(),
                                event.sourceRecordId()
                        );
                        gateway.tell(new GatewayActor.HandleRequest(new AgentRequest(requestId, prompt), replyTo));
                        system.log().info("Kafka runtime dispatched workflow request_id={} event_type={}", requestId, event.eventType());
                    } catch (Exception exception) {
                        system.log().error("Failed to parse inbound command event", exception);
                    }
                    return message.committableOffset();
                })
                .toMat(Committer.sink(committerSettings), org.apache.pekko.stream.javadsl.Keep.right())
                .run(materializer);

        completion.whenComplete((done, error) -> {
            if (error != null) {
                system.log().error("Kafka command stream failed", error);
            } else {
                system.log().info("Kafka command stream completed {}", streamCounter.get());
            }
        });
    }

    private static Properties producerProps(AppConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "pekko-runtime-events-producer");
        return props;
    }

    private static ToolWiring createToolWiring(AppConfig config) {
        return new DefaultToolWiring();
    }

    private static String promptFor(InboundCommandEvent event) {
        JsonNode payload = event.payload();
        return switch (event.eventType()) {
            case "GoogleBusinessProfileReviewReceived", "MockGoogleBusinessProfileReviewReceived" ->
                    "Summarize and propose a concise operational response for this Google Business Profile review.\n"
                            + "Tenant: " + event.tenantId() + "\n"
                            + "Source record: " + event.sourceRecordId() + "\n"
                            + "Reviewer: " + text(payload, "reviewerDisplayName") + "\n"
                            + "Rating: " + text(payload, "starRating") + "\n"
                            + "Comment: " + text(payload, "comment") + "\n";
            case "YouTubeCommentReceived", "MockYouTubeCommentReceived" ->
                    "Summarize and propose a concise operational response for this YouTube comment.\n"
                            + "Tenant: " + event.tenantId() + "\n"
                            + "Source record: " + event.sourceRecordId() + "\n"
                            + "Channel: " + text(payload, "channelId") + "\n"
                            + "Video: " + text(payload, "videoId") + "\n"
                            + "Author: " + text(payload, "authorDisplayName") + "\n"
                            + "Comment: " + text(payload, "textDisplay") + "\n";
            case "YouTubeChannelSnapshotCaptured" ->
                    "Analyze this YouTube channel snapshot for audience growth, subscriber growth, and brand-building.\n"
                            + "Return meaningful diagnostic and prescriptive insight. Use simple ranking labels such as high, medium, or low instead of numeric confidence.\n"
                            + "Include JSON-like sections named summary, diagnosis, severity, nextSteps, suggestions, and risks.\n"
                            + "Tenant: " + event.tenantId() + "\n"
                            + "Channel: " + text(payload, "channelId") + "\n"
                            + "Title: " + text(payload, "title") + "\n"
                            + "Country: " + text(payload, "country") + "\n"
                            + "Subscribers: " + text(payload, "subscriberCount") + "\n"
                            + "Hidden subscribers: " + text(payload, "hiddenSubscriberCount") + "\n"
                            + "Views: " + text(payload, "viewCount") + "\n"
                            + "Videos: " + text(payload, "videoCount") + "\n"
                            + "Comments: " + text(payload, "commentCount") + "\n"
                            + "Uploads playlist: " + text(payload, "uploadsPlaylistId") + "\n"
                            + "Description: " + text(payload, "description") + "\n";
            case "YouTubeChannelActivityCaptured" ->
                    "Analyze this YouTube channel activity event for content opportunities, audience-building, and likely cause-effect implications.\n"
                            + "Return meaningful diagnostic and prescriptive insight. Use simple ranking labels such as high, medium, or low instead of numeric confidence.\n"
                            + "Include JSON-like sections named summary, activitySignal, diagnosis, severity, nextSteps, suggestions, and risks.\n"
                            + "Tenant: " + event.tenantId() + "\n"
                            + "Activity record: " + event.sourceRecordId() + "\n"
                            + "Channel: " + text(payload, "channelId") + "\n"
                            + "Channel title: " + text(payload, "channelTitle") + "\n"
                            + "Activity type: " + text(payload, "activityType") + "\n"
                            + "Title: " + text(payload, "title") + "\n"
                            + "Video: " + text(payload, "videoId") + "\n"
                            + "Playlist: " + text(payload, "playlistId") + "\n"
                            + "Target channel: " + text(payload, "targetChannelId") + "\n"
                            + "Recommendation reason: " + text(payload, "recommendationReason") + "\n"
                            + "Description: " + text(payload, "description") + "\n";
            default ->
                    "Summarize and synthesize this inbound source item with concise context.\n"
                            + "Tenant: " + event.tenantId() + "\n"
                            + "Source: " + event.source() + "\n"
                            + "Source record: " + event.sourceRecordId() + "\n"
                            + "Title: " + text(payload, "title") + "\n"
                            + "URL: " + text(payload, "url") + "\n";
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return "";
        }
        return node.path(field).asText("");
    }

    public record InboundCommandEvent(
            String eventId,
            String eventType,
            int eventVersion,
            String tenantId,
            String source,
            String sourceRecordId,
            Instant occurredAt,
            JsonNode payload
    ) {
    }

    public record NewsSummaryGeneratedEvent(
            String eventId,
            String eventType,
            int eventVersion,
            String tenantId,
            String workflowId,
            String sourceEventId,
            String source,
            SummaryPayload payload
    ) {
        static NewsSummaryGeneratedEvent from(InboundCommandEvent source, AgentResponse response) {
            return new NewsSummaryGeneratedEvent(
                    UUID.randomUUID().toString(),
                    outputEventType(source),
                    1,
                    source.tenantId(),
                    "workflow-" + source.eventId(),
                    source.eventId(),
                    "pekko-agent-runtime",
                    new SummaryPayload(
                            source.sourceRecordId(),
                            response.isSuccess() ? response.output() : response.error().getMessage(),
                            "",
                            response.isSuccess() ? "medium" : "low"
                    )
            );
        }

        private static String outputEventType(InboundCommandEvent source) {
            return switch (source.eventType()) {
                case "YouTubeChannelSnapshotCaptured" -> "YouTubeChannelInsightGenerated";
                case "YouTubeChannelActivityCaptured" -> "YouTubeChannelActivityAnalysisGenerated";
                case "YouTubeCommentReceived", "MockYouTubeCommentReceived" -> "YouTubeCommentAnalysisGenerated";
                default -> "NewsSummaryGenerated";
            };
        }
    }

    public record SummaryPayload(
            String articleId,
            String summary,
            String topics,
            String confidence
    ) {
    }
}
