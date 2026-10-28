package com.practice.cashflow;
import com.practice.cashflow.adapter.out.messaging.SqsOutboxSender;
import com.practice.cashflow.application.port.out.OutboxStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SqsOutboxSenderTest {
 @Test void sendsPayloadCachesUrlAndSetsFiniteTimeouts() {
  var sqs=mock(SqsClient.class);when(sqs.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.example/review").build());
  when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().messageId("broker-id").build());
  var sender=new SqsOutboxSender(sqs,"review-queue");var claim=new OutboxStore.Claim(UUID.randomUUID(),UUID.randomUUID(),"{\"type\":\"payment.execute.v1\"}",1);
  sender.send(claim);sender.send(claim);verify(sqs,times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
  var captor=ArgumentCaptor.forClass(SendMessageRequest.class);verify(sqs,times(2)).sendMessage(captor.capture());
  assertEquals(claim.payload(),captor.getValue().messageBody());assertEquals(Duration.ofSeconds(10),captor.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
 }
 @Test void rejectsFifoQueueForThisStandardSender() {assertThrows(IllegalArgumentException.class,()->new SqsOutboxSender(mock(SqsClient.class),"review.fifo"));}
}
