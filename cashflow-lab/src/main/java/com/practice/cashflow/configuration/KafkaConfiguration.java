package com.practice.cashflow.configuration;

import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile("kafka")
public class KafkaConfiguration {
    @Bean(destroyMethod = "close")
    Admin kafkaProbeAdmin(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000));
    }

    @Bean
    NewTopic batchCompletedTopic(@Value("${app.kafka.batch-completed-topic}") String name) {
        // One local broker: replication factor 1 provides no broker failover.
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
