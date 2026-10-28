package com.practice.cashflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in: uses the running local broker, never a cloud broker by default. */
@EnabledIfSystemProperty(named = "cashflow.live-kafka", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("kafka")
class KafkaConnectivityTest {
    @Autowired Admin admin;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ConsumerFactory<String, String> consumers;

    @Test
    void javaPublishesAndConsumesTheSameSmokeMessage() throws Exception {
        String topic = "cashflow.connectivity.v1";
        try {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                    .configs(Map.of("retention.ms", "3600000"))))
                    .all().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (!(failure.getCause() instanceof TopicExistsException)) throw failure;
        }
        String key = UUID.randomUUID().toString();
        String message = "{\"kind\":\"connection-check\",\"realPayment\":false}";
        try (var consumer = consumers.createConsumer("cashflow-smoke-" + key, "connectivity")) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToEnd(List.of(partition));
            consumer.position(partition, Duration.ofSeconds(5));
            var sent = template.send(topic, 0, key, message).get(10, TimeUnit.SECONDS);
            boolean received = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!received && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        assertEquals(message, record.value());
                        assertEquals(sent.getRecordMetadata().offset(), record.offset());
                        received = true;
                    }
                }
            }
            assertTrue(received, "Java must read back its own newly published message");
        }
        assertFalse(admin.describeCluster().nodes().get(5, TimeUnit.SECONDS).isEmpty());
    }
}
