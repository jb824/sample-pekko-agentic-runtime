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
        Map<String, NewsArticleReceivedEvent> pending = new ConcurrentHashMap<>();

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
                        NewsArticleReceivedEvent inbound = pending.remove(response.requestId());
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
            Map<String, NewsArticleReceivedEvent> pending
    ) {
        ConsumerSettings<String, String> settings = ConsumerSettings.create(system, new StringDeserializer(), new StringDeserializer())
                .withBootstrapServers(config.kafkaBootstrapServers())
                .withGroupId(config.kafkaGroupId())
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        CommitterSettings committerSettings = CommitterSettings.create(system);
        Materializer materializer = SystemMaterializer.get(system).materializer();
        AtomicInteger streamCounter = new AtomicInteger();

        CompletionStage<Done> completion = Consumer.committableSource(settings, Subscriptions.topics(config.kafkaInputTopic()))
                .map(message -> {
                    ConsumerRecord<String, String> record = message.record();
                    try {
                        NewsArticleReceivedEvent event = mapper.readValue(record.value(), NewsArticleReceivedEvent.class);
                        String requestId = event.eventId();
                        pending.put(requestId, event);
                        String prompt = "Summarize and synthesize this news article with concise context.\n"
                                + "Title: " + event.payload().title() + "\n"
                                + "URL: " + event.payload().url() + "\n";
                        gateway.tell(new GatewayActor.HandleRequest(new AgentRequest(requestId, prompt), replyTo));
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

    public record NewsArticleReceivedEvent(
            String eventId,
            String eventType,
            int eventVersion,
            String tenantId,
            String source,
            Instant occurredAt,
            NewsArticlePayload payload
    ) {
    }

    public record NewsArticlePayload(String articleId, String title, String url) {
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
        static NewsSummaryGeneratedEvent from(NewsArticleReceivedEvent source, AgentResponse response) {
            return new NewsSummaryGeneratedEvent(
                    UUID.randomUUID().toString(),
                    "NewsSummaryGenerated",
                    1,
                    source.tenantId(),
                    "workflow-" + source.eventId(),
                    source.eventId(),
                    "pekko-agent-runtime",
                    new SummaryPayload(
                            source.payload().articleId(),
                            response.isSuccess() ? response.output() : response.error().getMessage(),
                            "",
                            response.isSuccess() ? "medium" : "low"
                    )
            );
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
