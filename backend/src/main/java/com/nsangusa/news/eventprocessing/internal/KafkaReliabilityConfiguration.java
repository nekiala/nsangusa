package com.nsangusa.news.eventprocessing.internal;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableKafkaRetryTopic
class KafkaReliabilityConfiguration {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> operations) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            operations,
            (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    var backOff = new ExponentialBackOff(1_000, 2.0);
    backOff.setMaxInterval(30_000);
    backOff.setMaxElapsedTime(120_000);
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, jakarta.validation.ConstraintViolationException.class);
    return handler;
  }
}
