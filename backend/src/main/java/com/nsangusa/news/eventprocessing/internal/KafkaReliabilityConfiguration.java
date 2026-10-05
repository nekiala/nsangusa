package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.eventprocessing.TerminalEventException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaReliabilityConfiguration {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaOperations<Object, Object> operations, DelayedRetryPolicy retries) {
    var backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1_000);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(4_000);
    return errorHandler(recoverer(operations, retries), backOff);
  }

  @Bean
  ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
      reliabilityContainerCustomizer(
          KafkaOperations<Object, Object> operations, DelayedRetryPolicy retries) {
    // Each delayed-retry delivery is one attempt; its record's delay already supplied the backoff.
    var retryErrors = errorHandler(recoverer(operations, retries), new FixedBackOff(0, 0));
    return container -> {
      var properties = container.getContainerProperties();
      properties.setDeliveryAttemptHeader(true);
      if ("failure-metadata-v1".equals(properties.getGroupId())) {
        // Never consume-and-discard metadata or recursively produce .dlt.dlt on a DB outage.
        var metadataErrors =
            new DefaultErrorHandler(new FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS));
        metadataErrors.setClassifications(Map.of(), true);
        container.setCommonErrorHandler(metadataErrors);
      } else if (DelayedRetryPolicy.retryTwinOf(properties.getGroupId()) != null) {
        // The interceptor waits for each record's delay; one record per poll bounds that wait.
        properties
            .getKafkaConsumerProperties()
            .setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        container.setCommonErrorHandler(retryErrors);
      }
    };
  }

  private static DeadLetterPublishingRecoverer recoverer(
      KafkaOperations<Object, Object> operations, DelayedRetryPolicy retries) {
    var recoverer = new DeadLetterPublishingRecoverer(operations, retries::destination);
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
    // The first forward's original topic, partition, offset and group survive later forwards.
    recoverer.setAppendOriginalHeaders(false);
    // A missing matching retry/DLT partition is a provisioning error, not permission to reroute.
    recoverer.setVerifyPartition(false);
    recoverer.addHeadersFunction(retries::headers);
    recoverer.setExceptionHeadersCreator(
        (headers, exception, key, names) -> {
          String category = EventFailurePolicy.category(exception);
          headers.remove(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
          headers.remove(KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
          headers.remove(KafkaHeaders.DLT_EXCEPTION_FQCN);
          headers.remove(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
          headers.remove(EventFailurePolicy.CATEGORY_HEADER);
          headers.add(
              KafkaHeaders.DLT_EXCEPTION_FQCN,
              EventFailurePolicy.rootCause(exception)
                  .getClass()
                  .getName()
                  .getBytes(StandardCharsets.UTF_8));
          headers.add(
              KafkaHeaders.DLT_EXCEPTION_MESSAGE,
              EventFailurePolicy.safeMessage(category).getBytes(StandardCharsets.UTF_8));
          headers.add(
              EventFailurePolicy.CATEGORY_HEADER, category.getBytes(StandardCharsets.UTF_8));
        });
    return recoverer;
  }

  private static DefaultErrorHandler errorHandler(
      DeadLetterPublishingRecoverer recoverer, BackOff backOff) {
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class,
        jakarta.validation.ConstraintViolationException.class,
        org.springframework.security.access.AccessDeniedException.class,
        org.springframework.security.core.AuthenticationException.class,
        TerminalEventException.class);
    handler.setResetStateOnExceptionChange(false);
    handler.setResetStateOnRecoveryFailure(false);
    handler.setRetryListeners(
        (record, exception, attempt) -> {
          // Count deliveries across the main topic and every delayed retry before this one.
          int deliveries = DelayedRetryPolicy.priorDeliveries(record) + attempt;
          record.headers().remove(EventFailurePolicy.ATTEMPT_HEADER);
          record
              .headers()
              .add(
                  EventFailurePolicy.ATTEMPT_HEADER,
                  ByteBuffer.allocate(4).putInt(deliveries).array());
        });
    return handler;
  }
}
