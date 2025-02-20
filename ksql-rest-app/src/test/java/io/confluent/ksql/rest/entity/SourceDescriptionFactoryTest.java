/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.entity;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KsqlStream;
import io.confluent.ksql.metrics.ConsumerCollector;
import io.confluent.ksql.metrics.StreamsErrorCollector;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.util.timestamp.MetadataTimestampExtractionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SourceDescriptionFactoryTest {
  private final static String CLIENT_ID = "client";
  private final static String APP_ID = "test-app";

  private ConsumerCollector consumerCollector;

  @Before
  public void setUp() {
    consumerCollector = new ConsumerCollector();
    consumerCollector.configure(
        Collections.singletonMap(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID));
  }

  @After
  public void tearDown() {
    StreamsErrorCollector.notifyApplicationClose(APP_ID);
    consumerCollector.close();
  }

  private static DataSource<?> buildDataSource(final String kafkaTopicName) {
    final LogicalSchema schema = LogicalSchema.builder()
        .valueColumn(ColumnName.of("field0"), SqlTypes.INTEGER)
        .build();

    final KsqlTopic topic = new KsqlTopic(
        kafkaTopicName,
        KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
        ValueFormat.of(FormatInfo.of(Format.JSON)),
        true
    );

    return new KsqlStream<>(
        "query",
        SourceName.of("stream"),
        schema,
        SerdeOption.none(),
        KeyField.of(schema.value().get(0).name(), schema.value().get(0)),
        new MetadataTimestampExtractionPolicy(),
        topic
    );
  }

  private static ConsumerRecords<Object, Object> buildRecords(final String kafkaTopicName) {
    return new ConsumerRecords<>(
        ImmutableMap.of(
            new TopicPartition(kafkaTopicName, 1),
            Arrays.asList(
                new ConsumerRecord<>(
                    kafkaTopicName, 1, 1, 1L, TimestampType.CREATE_TIME, 1L,
                    10, 10, "key", "1234567890")
            )
        )
    );
  }

  @Test
  public void shouldReturnStatsBasedOnKafkaTopic() {
    // Given:
    final String kafkaTopicName = "kafka";
    final DataSource<?> dataSource = buildDataSource(kafkaTopicName);
    consumerCollector.onConsume(buildRecords(kafkaTopicName));
    StreamsErrorCollector.recordError(APP_ID, kafkaTopicName);

    // When
    final SourceDescription sourceDescription = SourceDescriptionFactory.create(
        dataSource,
        true,
        "json",
        Collections.emptyList(),
        Collections.emptyList(),
        Optional.empty());

    // Then:
    assertThat(
        sourceDescription.getStatistics(),
        containsString(ConsumerCollector.CONSUMER_TOTAL_MESSAGES));
    assertThat(
        sourceDescription.getErrorStats(),
        containsString(StreamsErrorCollector.CONSUMER_FAILED_MESSAGES));
  }
}
