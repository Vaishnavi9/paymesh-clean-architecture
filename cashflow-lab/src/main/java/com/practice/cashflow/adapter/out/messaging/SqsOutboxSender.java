package com.practice.cashflow.adapter.out.messaging;

import com.practice.cashflow.application.port.out.OutboxSender;
import com.practice.cashflow.application.port.out.OutboxStore;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Duration;

public class SqsOutboxSender implements OutboxSender {

    private final SqsClient sqsClient;
    private final String queueName;

    private volatile String queueUrl;


    public SqsOutboxSender(SqsClient sqsClient, String queueName) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("Queue name is required");
        }

        if (queueName.endsWith(".fifo")) {
            throw new IllegalArgumentException(
                    "This sender is configured for a Standard queue"
            );
        }
        this.sqsClient = sqsClient;
        this.queueName = queueName;
    }


    @Override
    public void send(OutboxStore.Claim message) {
        var request = SendMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .messageBody(message.payload())
                .overrideConfiguration(
                        configuration ->
                                configuration
                                        .apiCallAttemptTimeout(Duration.ofSeconds(5))
                                        .apiCallTimeout(Duration.ofSeconds(10)))

                .build();

        sqsClient.sendMessage(request);

    }

    private String resolveQueueUrl() {
        String current = queueUrl;

        if (current != null) {
            return current;
        }

        var request = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .overrideConfiguration(configuration ->
                        configuration.apiCallAttemptTimeout(Duration.ofSeconds(5))
                                .apiCallTimeout(Duration.ofSeconds(10))
                )
                .build();

        current = sqsClient.getQueueUrl(request).queueUrl();
        queueUrl = current;

        return current;
    }
}
