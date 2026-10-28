package com.practice.cashflow.configuration;


import com.practice.cashflow.adapter.in.scheduling.OutboxDeliveryScheduler;
import com.practice.cashflow.adapter.out.messaging.SqsOutboxSender;
import com.practice.cashflow.adapter.out.persistence.JdbcOutboxStore;
import com.practice.cashflow.application.OutboxPublisher;
import com.practice.cashflow.application.port.out.OutboxSender;
import com.practice.cashflow.application.port.out.OutboxStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("db & acceptance & aws & outbox")
public class OutboxDeliveryConfiguration {

    @Bean
    OutboxStore outboxStore(
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        return new JdbcOutboxStore(namedParameterJdbcTemplate, transactionTemplate);
    }

    @Bean
    OutboxSender outboxSender(
            SqsClient sqs,
            @Value("${app.aws.sqs.queue-name}") String queueName
    ) {
        return new SqsOutboxSender(sqs, queueName);
    }

    @Bean
    OutboxPublisher outboxPublisher(
            OutboxStore store,
            OutboxSender outboxSender
    ) {
        return new OutboxPublisher(store, outboxSender);
    }

    @Bean(destroyMethod = "close")
    OutboxDeliveryScheduler outboxDeliveryScheduler(
            OutboxPublisher outboxPublisher
    ) {
        return new OutboxDeliveryScheduler(outboxPublisher);
    }
}
