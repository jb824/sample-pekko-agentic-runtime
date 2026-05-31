package com.example.agent.kafka;

import com.example.agent.config.AppConfig;
import com.example.agent.guardian.GuardianArticle;
import com.example.agent.guardian.GuardianClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class GuardianKafkaIngestionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardianKafkaIngestionRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private GuardianKafkaIngestionRunner() {
    }

    public static void run(AppConfig appConfig, Config config) {
        GuardianClient guardianClient = new GuardianClient(config);
        List<GuardianArticle> articles = guardianClient.search(
                appConfig.guardianQuery(),
                appConfig.guardianSection(),
                appConfig.guardianMaxPages()
        );

        if (articles.isEmpty()) {
            LOGGER.info("No Guardian articles returned for query='{}' section='{}'",
                    appConfig.guardianQuery(), appConfig.guardianSection());
            return;
        }

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(appConfig))) {
            for (GuardianArticle article : articles) {
                String payload = JSON.writeValueAsString(envelope(article));
                producer.send(new ProducerRecord<>(appConfig.kafkaTopic(), article.id(), payload)).get();
            }
            producer.flush();
            LOGGER.info(
                    "Published {} Guardian article events to Kafka topic='{}' bootstrap='{}'",
                    articles.size(),
                    appConfig.kafkaTopic(),
                    appConfig.kafkaBootstrapServers()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Guardian Kafka ingestion failed", exception);
        }
    }

    private static Properties producerProperties(AppConfig appConfig) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, appConfig.kafkaBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "guardian-ingestion-producer");
        return properties;
    }

    private static Map<String, Object> envelope(GuardianArticle article) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", article.id());
        payload.put("sectionId", article.sectionId());
        payload.put("sectionName", article.sectionName());
        payload.put("webTitle", article.webTitle());
        payload.put("webUrl", article.webUrl());
        payload.put("webPublicationDate", article.webPublicationDate().toString());
        payload.put("headline", article.headline());
        payload.put("trailText", article.trailText());
        payload.put("byline", article.byline());
        payload.put("publication", article.publication());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "GuardianArticleIngested");
        event.put("eventVersion", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("tenantId", "default");
        event.put("workflowId", "guardian-ingestion");
        event.put("correlationId", article.id());
        event.put("source", "pekko-agent-runtime");
        event.put("schema", "agent.guardian.GuardianArticleIngested.v1");
        event.put("payload", payload);
        return event;
    }
}
