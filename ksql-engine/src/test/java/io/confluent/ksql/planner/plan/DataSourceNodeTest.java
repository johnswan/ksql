/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.ksql.planner.plan;

import static io.confluent.ksql.metastore.model.DataSource.DataSourceType;
import static io.confluent.ksql.planner.plan.PlanTestUtil.getNodeByName;
import static io.confluent.ksql.planner.plan.PlanTestUtil.verifyProcessorNode;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.context.QueryLoggerUtil;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.plan.StreamSource;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KsqlStream;
import io.confluent.ksql.metastore.model.KsqlTable;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.KeySerde;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.serde.WindowInfo;
import io.confluent.ksql.structured.SchemaKStream;
import io.confluent.ksql.structured.SchemaKTable;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.timestamp.LongColumnTimestampExtractionPolicy;
import io.confluent.ksql.util.timestamp.TimestampExtractionPolicy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.Topology.AutoOffsetReset;
import org.apache.kafka.streams.TopologyDescription;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.kstream.ValueMapperWithKey;
import org.apache.kafka.streams.kstream.ValueTransformerSupplier;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@RunWith(MockitoJUnitRunner.class)
public class DataSourceNodeTest {

  private static final String TIMESTAMP_FIELD = "timestamp";
  private static final PlanNodeId PLAN_NODE_ID = new PlanNodeId("0");

  private final KsqlConfig realConfig = new KsqlConfig(Collections.emptyMap());
  private SchemaKStream realStream;
  private StreamsBuilder realBuilder;
  private static final LogicalSchema REAL_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("field1"), SqlTypes.STRING)
      .valueColumn(ColumnName.of("field2"), SqlTypes.STRING)
      .valueColumn(ColumnName.of("field3"), SqlTypes.STRING)
      .valueColumn(ColumnName.of(TIMESTAMP_FIELD), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("key"), SqlTypes.STRING)
      .build();
  private static final KeyField KEY_FIELD
      = KeyField.of(ColumnName.of("field1"), REAL_SCHEMA.findValueColumn("field1").get());
  private static final Optional<AutoOffsetReset> OFFSET_RESET = Optional.of(AutoOffsetReset.LATEST);

  private static final PhysicalSchema PHYSICAL_SCHEMA = PhysicalSchema
      .from(REAL_SCHEMA.withoutMetaAndKeyColsInValue(), SerdeOption.none());

  private final KsqlStream<String> SOME_SOURCE = new KsqlStream<>(
      "sqlExpression",
      SourceName.of("datasource"),
      REAL_SCHEMA,
      SerdeOption.none(),
      KeyField.of(ColumnName.of("key"), REAL_SCHEMA.findValueColumn("key").get()),
      new LongColumnTimestampExtractionPolicy("timestamp"),
      new KsqlTopic(
          "topic",
          KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
          ValueFormat.of(FormatInfo.of(Format.JSON)),
          false
      )
  );

  private final DataSourceNode node = new DataSourceNode(
      PLAN_NODE_ID,
      SOME_SOURCE,
      SOME_SOURCE.getName()
  );

  private final QueryId queryId = new QueryId("source-test");

  private final PlanNodeId realNodeId = new PlanNodeId("source");
  @Mock
  private DataSource<?> dataSource;
  @Mock
  private TimestampExtractionPolicy timestampExtractionPolicy;
  @Mock
  private TimestampExtractor timestampExtractor;
  @Mock
  private KsqlTopic ksqlTopic;
  @Mock
  private Serde<GenericRow> rowSerde;
  @Mock
  private KeySerde<String> keySerde;
  @Mock
  private StreamsBuilder streamsBuilder;
  @Mock
  private KStream<?, ?> kStream;
  @Mock
  private KGroupedStream kGroupedStream;
  @Mock
  private KTable kTable;
  @Mock
  private KsqlQueryBuilder ksqlStreamBuilder;
  @Mock
  private FunctionRegistry functionRegistry;
  @Mock
  private DataSourceNode.SchemaKStreamFactory schemaKStreamFactory;
  @Captor
  private ArgumentCaptor<QueryContext> queryContextCaptor;
  @Captor
  private ArgumentCaptor<QueryContext.Stacker> stackerCaptor;
  @Mock
  private SchemaKStream stream;
  @Mock
  private SchemaKTable table;

  private final Set<SerdeOption> serdeOptions = SerdeOption.none();

  @Before
  @SuppressWarnings("unchecked")
  public void before() {
    realBuilder = new StreamsBuilder();

    when(ksqlStreamBuilder.getKsqlConfig()).thenReturn(realConfig);
    when(ksqlStreamBuilder.getStreamsBuilder()).thenReturn(realBuilder);
    when(ksqlStreamBuilder.buildNodeContext(any())).thenAnswer(inv ->
        new QueryContext.Stacker(queryId)
            .push(inv.getArgument(0).toString()));

    when(ksqlStreamBuilder.buildKeySerde(any(), any(), any()))
        .thenReturn((KeySerde)keySerde);
    when(ksqlStreamBuilder.buildKeySerde(any(), any(), any(), any())).thenReturn((KeySerde)keySerde);
    when(ksqlStreamBuilder.buildValueSerde(any(), any(), any())).thenReturn(rowSerde);
    when(ksqlStreamBuilder.getFunctionRegistry()).thenReturn(functionRegistry);

    when(rowSerde.serializer()).thenReturn(mock(Serializer.class));
    when(rowSerde.deserializer()).thenReturn(mock(Deserializer.class));

    when(dataSource.getKsqlTopic()).thenReturn(ksqlTopic);
    when(dataSource.getDataSourceType()).thenReturn(DataSourceType.KTABLE);
    when(dataSource.getTimestampExtractionPolicy()).thenReturn(timestampExtractionPolicy);
    when(dataSource.getSerdeOptions()).thenReturn(serdeOptions);
    when(ksqlTopic.getKafkaTopicName()).thenReturn("topic");
    when(ksqlTopic.getKeyFormat()).thenReturn(KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)));
    when(ksqlTopic.getValueFormat()).thenReturn(ValueFormat.of(FormatInfo.of(Format.JSON)));
    when(timestampExtractionPolicy.timestampField()).thenReturn(TIMESTAMP_FIELD);
    when(timestampExtractionPolicy.create(anyInt())).thenReturn(timestampExtractor);
    when(kStream.transformValues(any(ValueTransformerSupplier.class))).thenReturn(kStream);
    when(kStream.mapValues(any(ValueMapperWithKey.class))).thenReturn(kStream);
    when(kStream.mapValues(any(ValueMapper.class))).thenReturn(kStream);
    when(kStream.groupByKey()).thenReturn(kGroupedStream);
    when(kGroupedStream.aggregate(any(), any(), any())).thenReturn(kTable);
    when(schemaKStreamFactory.create(any(), any(), any(), any(), anyInt(), any(), any()))
        .thenReturn(stream);
    when(stream.toTable(any(), any(), any(), any())).thenReturn(table);
  }

  @Test
  public void shouldCreateLoggerForSourceSerde() {
    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(ksqlStreamBuilder).buildValueSerde(
        any(),
        any(),
        queryContextCaptor.capture()
    );

    assertThat(QueryLoggerUtil.queryLoggerName(queryContextCaptor.getValue()),
        is("source-test.0.source"));
  }

  @Test
  public void shouldBuildSourceNode() {
    // When:
    realStream = node.buildStream(ksqlStreamBuilder);

    // Then:
    final TopologyDescription.Source node = (TopologyDescription.Source) getNodeByName(realBuilder.build(), PlanTestUtil.SOURCE_NODE);
    final List<String> successors = node.successors().stream().map(TopologyDescription.Node::name).collect(Collectors.toList());
    assertThat(node.predecessors(), equalTo(Collections.emptySet()));
    assertThat(successors, equalTo(Collections.singletonList(PlanTestUtil.MAPVALUES_NODE)));
    assertThat(node.topicSet(), equalTo(ImmutableSet.of("topic")));
  }

  @Test
  public void shouldBuildMapNode() {
    // When:
    realStream = node.buildStream(ksqlStreamBuilder);

    // Then:
    verifyProcessorNode((TopologyDescription.Processor) getNodeByName(realBuilder.build(), PlanTestUtil.MAPVALUES_NODE),
        Collections.singletonList(PlanTestUtil.SOURCE_NODE),
        Collections.singletonList(PlanTestUtil.TRANSFORM_NODE));
  }

  @Test
  public void shouldBuildTransformNode() {
    // When:
    realStream = node.buildStream(ksqlStreamBuilder);

    // Then:
    final TopologyDescription.Processor node = (TopologyDescription.Processor) getNodeByName(
        realBuilder.build(), PlanTestUtil.TRANSFORM_NODE);
    verifyProcessorNode(node, Collections.singletonList(PlanTestUtil.MAPVALUES_NODE), Collections.emptyList());
  }

  @Test
  public void shouldBeOfTypeSchemaKStreamWhenDataSourceIsKsqlStream() {
    // When:
    realStream = node.buildStream(ksqlStreamBuilder);

    // Then:
    assertThat(realStream.getClass(), equalTo(SchemaKStream.class));
  }

  @Test
  public void shouldBuildStreamWithSameKeyField() {
    // When:
    final SchemaKStream<?> stream = node.buildStream(ksqlStreamBuilder);

    // Then:
    assertThat(stream.getKeyField(), is(node.getKeyField()));
  }

  @Test
  public void shouldBuildSchemaKTableWhenKTableSource() {
    final KsqlTable<String> table = new KsqlTable<>("sqlExpression",
        SourceName.of("datasource"),
        REAL_SCHEMA,
        SerdeOption.none(),
        KeyField.of(ColumnName.of("field1"), REAL_SCHEMA.findValueColumn("field1").get()),
        new LongColumnTimestampExtractionPolicy("timestamp"),
        new KsqlTopic(
            "topic2",
            KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
            ValueFormat.of(FormatInfo.of(Format.JSON)),
            false
        )
    );

    final DataSourceNode node = new DataSourceNode(
        PLAN_NODE_ID,
        table,
        table.getName());

    final SchemaKStream result = node.buildStream(ksqlStreamBuilder);
    assertThat(result.getClass(), equalTo(SchemaKTable.class));
  }

  @Test
  public void shouldTransformKStreamToKTableCorrectly() {
    final KsqlTable<String> table = new KsqlTable<>("sqlExpression",
        SourceName.of("datasource"),
        REAL_SCHEMA,
        SerdeOption.none(),
        KeyField.of(ColumnName.of("field1"), REAL_SCHEMA.findValueColumn("field1").get()),
        new LongColumnTimestampExtractionPolicy("timestamp"),
        new KsqlTopic(
            "topic2",
            KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
            ValueFormat.of(FormatInfo.of(Format.JSON)),
            false
        )
    );

    final DataSourceNode node = new DataSourceNode(
        PLAN_NODE_ID,
        table,
        table.getName());

    realBuilder = new StreamsBuilder();
    when(ksqlStreamBuilder.getStreamsBuilder()).thenReturn(realBuilder);
    node.buildStream(ksqlStreamBuilder);
    final Topology topology = realBuilder.build();
    final TopologyDescription description = topology.describe();

    final List<String> expectedPlan = Arrays.asList(
        "SOURCE", "MAPVALUES", "TRANSFORMVALUES", "MAPVALUES", "AGGREGATE");

    assertThat(description.subtopologies().size(), equalTo(1));
    final Set<TopologyDescription.Node> nodes = description.subtopologies().iterator().next().nodes();
    // Get the source node
    TopologyDescription.Node streamsNode = nodes.iterator().next();
    while (!streamsNode.predecessors().isEmpty()) {
      streamsNode = streamsNode.predecessors().iterator().next();
    }
    // Walk the plan and make sure it matches
    final ListIterator<String> expectedPlanIt = expectedPlan.listIterator();
    assertThat(nodes.size(), equalTo(expectedPlan.size()));
    while (true) {
      assertThat(streamsNode.name(), startsWith("KSTREAM-" + expectedPlanIt.next()));
      if (streamsNode.successors().isEmpty()) {
        assertThat(expectedPlanIt.hasNext(), is(false));
        break;
      }
      assertThat(expectedPlanIt.hasNext(), is(true));
      assertThat(streamsNode.successors().size(), equalTo(1));
      streamsNode = streamsNode.successors().iterator().next();
    }
  }

  @Test
  public void shouldHaveFullyQualifiedSchema() {
    // Given:
    final SourceName sourceName = SOME_SOURCE.getName();

    // When:
    final LogicalSchema schema = node.getSchema();

    // Then:
    assertThat(schema, is(
        LogicalSchema.builder()
            .valueColumn(SchemaUtil.ROWTIME_NAME, SqlTypes.BIGINT)
            .valueColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
            .valueColumn(ColumnName.of("field1"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("field2"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("field3"), SqlTypes.STRING)
            .valueColumn(ColumnName.of(TIMESTAMP_FIELD), SqlTypes.BIGINT)
            .valueColumn(ColumnName.of("key"), SqlTypes.STRING)
            .build().withAlias(sourceName)));
  }

  @Test
  public void shouldBuildNonWindowedKeySerde() {
    // Given:
    final DataSourceNode node = nodeWithMockTableSource();

    final KeyFormat keyFormat = KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA));
    when(ksqlTopic.getKeyFormat()).thenReturn(keyFormat);

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(ksqlStreamBuilder, times(2)).buildKeySerde(
        eq(keyFormat.getFormatInfo()),
        eq(PHYSICAL_SCHEMA),
        any());
  }

  @Test
  public void shouldBuildWindowedKeySerde() {
    // Given:
    final DataSourceNode node = nodeWithMockTableSource();

    final KeyFormat keyFormat = KeyFormat.windowed(
        FormatInfo.of(Format.KAFKA),
        WindowInfo.of(WindowType.TUMBLING, Optional.of(Duration.ofSeconds(10)))
    );

    when(ksqlTopic.getKeyFormat()).thenReturn(keyFormat);

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(ksqlStreamBuilder, times(2)).buildKeySerde(
        eq(keyFormat.getFormatInfo()),
        eq(keyFormat.getWindowInfo().get()),
        eq(PHYSICAL_SCHEMA),
        any());
  }

  @Test
  public void shouldBuildSourceValueSerdeWithSourceSchema() {
    // Given:
    final DataSourceNode node = nodeWithMockTableSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(ksqlStreamBuilder).buildValueSerde(
        eq(FormatInfo.of(Format.JSON)),
        eq(PHYSICAL_SCHEMA),
        any());
  }

  @Test
  public void shouldBuildSourceStreamWithCorrectTimestampIndex() {
    // Given:
    reset(timestampExtractionPolicy);
    when(timestampExtractionPolicy.timestampField()).thenReturn("field2");
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(schemaKStreamFactory).create(any(), any(), any(), any(), eq(1), any(), any());
  }

  @Test
  public void shouldBuildSourceStreamWithCorrectTimestampIndexForQualifiedFieldName() {
    // Given:
    reset(timestampExtractionPolicy);
    when(timestampExtractionPolicy.timestampField()).thenReturn("name.field2");
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(schemaKStreamFactory).create(any(), any(), any(), any(), eq(1), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldBuildSourceStreamWithCorrectParams() {
    // Given:
    when(dataSource.getDataSourceType()).thenReturn(DataSourceType.KSTREAM);
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    final SchemaKStream returned = node.buildStream(ksqlStreamBuilder);

    // Then:
    assertThat(returned, is(stream));
    verify(schemaKStreamFactory).create(
        same(ksqlStreamBuilder),
        same(dataSource),
        eq(StreamSource.getSchemaWithMetaAndKeyFields(SourceName.of("name"), REAL_SCHEMA)),
        stackerCaptor.capture(),
        eq(3),
        eq(OFFSET_RESET),
        same(node.getKeyField())
    );
    assertThat(
        stackerCaptor.getValue().getQueryContext().getContext(),
        equalTo(ImmutableList.of("0", "source"))
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldBuildSourceStreamWithCorrectParamsWhenBuildingTable() {
    // Given:
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(schemaKStreamFactory).create(
        same(ksqlStreamBuilder),
        same(dataSource),
        eq(StreamSource.getSchemaWithMetaAndKeyFields(SourceName.of("name"), REAL_SCHEMA)),
        stackerCaptor.capture(),
        eq(3),
        eq(OFFSET_RESET),
        same(node.getKeyField())
    );
    assertThat(
        stackerCaptor.getValue().getQueryContext().getContext(),
        equalTo(ImmutableList.of("0", "source"))
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldBuildTableByConvertingFromStream() {
    // Given:
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    final SchemaKStream returned = node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(stream).toTable(any(), any(), any(), any());
    assertThat(returned, is(table));
  }

  @SuppressWarnings("unchecked")
  public void shouldPassBuilderWhenBuildingTable() {
    // Given:
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(stream).toTable(any(), any(), any(), same(ksqlStreamBuilder));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldBuildTableWithCorrectContext() {
    // Given:
    final DataSourceNode node = buildNodeWithMockSource();

    // When:
    node.buildStream(ksqlStreamBuilder);

    // Then:
    verify(stream).toTable(any(), any(), stackerCaptor.capture(), any());
    assertThat(
        stackerCaptor.getValue().getQueryContext().getContext(),
        equalTo(ImmutableList.of("0", "reduce")));
  }


  @SuppressWarnings("unchecked")
  private DataSourceNode nodeWithMockTableSource() {
    when(ksqlStreamBuilder.getStreamsBuilder()).thenReturn(streamsBuilder);
    when(streamsBuilder.stream(anyString(), any())).thenReturn((KStream)kStream);
    when(dataSource.getSchema()).thenReturn(REAL_SCHEMA);
    when(dataSource.getKeyField())
        .thenReturn(KeyField.of(ColumnName.of("field1"), REAL_SCHEMA.findValueColumn("field1").get()));

    return new DataSourceNode(
        realNodeId,
        dataSource,
        SourceName.of("t")
    );
  }

  private DataSourceNode buildNodeWithMockSource() {
    when(dataSource.getSchema()).thenReturn(REAL_SCHEMA);
    when(dataSource.getKeyField()).thenReturn(KEY_FIELD);
    return new DataSourceNode(
        PLAN_NODE_ID,
        dataSource,
        SourceName.of("name"),
        schemaKStreamFactory
    );
  }
}
