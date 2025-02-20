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

package io.confluent.ksql.structured;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.StreamAggregate;
import io.confluent.ksql.execution.plan.StreamWindowedAggregate;
import io.confluent.ksql.execution.streams.ExecutionStepFactory;
import io.confluent.ksql.execution.streams.MaterializedFactory;
import io.confluent.ksql.execution.streams.StreamAggregateBuilder;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.parser.tree.WindowExpression;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.KeySerde;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.serde.WindowInfo;
import io.confluent.ksql.util.KsqlConfig;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Windowed;

public class SchemaKGroupedStream {

  final KGroupedStream kgroupedStream;
  final ExecutionStep<KGroupedStream<Struct, GenericRow>> sourceStep;
  final KeyFormat keyFormat;
  final KeySerde<Struct> keySerde;
  final KeyField keyField;
  final List<SchemaKStream> sourceSchemaKStreams;
  final KsqlConfig ksqlConfig;
  final FunctionRegistry functionRegistry;
  final MaterializedFactory materializedFactory;

  SchemaKGroupedStream(
      final KGroupedStream kgroupedStream,
      final ExecutionStep<KGroupedStream<Struct, GenericRow>> sourceStep,
      final KeyFormat keyFormat,
      final KeySerde<Struct> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry
  ) {
    this(
        kgroupedStream,
        sourceStep,
        keyFormat,
        keySerde,
        keyField,
        sourceSchemaKStreams,
        ksqlConfig,
        functionRegistry,
        MaterializedFactory.create(ksqlConfig)
    );
  }

  SchemaKGroupedStream(
      final KGroupedStream kgroupedStream,
      final ExecutionStep<KGroupedStream<Struct, GenericRow>> sourceStep,
      final KeyFormat keyFormat,
      final KeySerde<Struct> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final MaterializedFactory materializedFactory
  ) {
    this.kgroupedStream = kgroupedStream;
    this.sourceStep = sourceStep;
    this.keyFormat = Objects.requireNonNull(keyFormat, "keyFormat");
    this.keySerde = Objects.requireNonNull(keySerde, "keySerde");
    this.keyField = keyField;
    this.sourceSchemaKStreams = sourceSchemaKStreams;
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
    this.functionRegistry = functionRegistry;
    this.materializedFactory = materializedFactory;
  }

  public KeyField getKeyField() {
    return keyField;
  }

  public ExecutionStep<KGroupedStream<Struct, GenericRow>> getSourceStep() {
    return sourceStep;
  }

  @SuppressWarnings("unchecked")
  public SchemaKTable<?> aggregate(
      final LogicalSchema aggregateSchema,
      final LogicalSchema outputSchema,
      final int nonFuncColumnCount,
      final List<FunctionCall> aggregations,
      final Optional<WindowExpression> windowExpression,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    throwOnValueFieldCountMismatch(outputSchema, nonFuncColumnCount, aggregations);

    final ExecutionStep<? extends KTable<?, GenericRow>> step;
    final KTable table;
    final KeySerde<?> newKeySerde;
    final KeyFormat keyFormat;

    if (windowExpression.isPresent()) {
      keyFormat = getKeyFormat(windowExpression.get());
      newKeySerde = getKeySerde(windowExpression.get());
      final StreamWindowedAggregate aggregate = ExecutionStepFactory.streamWindowedAggregate(
          contextStacker,
          sourceStep,
          outputSchema,
          Formats.of(keyFormat, valueFormat, SerdeOption.none()),
          nonFuncColumnCount,
          aggregations,
          aggregateSchema,
          windowExpression.get().getKsqlWindowExpression()
      );
      step = aggregate;
      table = StreamAggregateBuilder.build(
          kgroupedStream,
          aggregate,
          queryBuilder,
          materializedFactory
      );
    } else {
      keyFormat = this.keyFormat;
      newKeySerde = keySerde;
      final StreamAggregate aggregate = ExecutionStepFactory.streamAggregate(
          contextStacker,
          sourceStep,
          outputSchema,
          Formats.of(keyFormat, valueFormat, SerdeOption.none()),
          nonFuncColumnCount,
          aggregations,
          aggregateSchema
      );
      step = aggregate;
      table = StreamAggregateBuilder.build(
          kgroupedStream,
          aggregate,
          queryBuilder,
          materializedFactory
      );
    }

    return new SchemaKTable(
        table,
        step,
        keyFormat,
        newKeySerde,
        keyField,
        sourceSchemaKStreams,
        SchemaKStream.Type.AGGREGATE,
        ksqlConfig,
        functionRegistry
    );
  }

  private KeyFormat getKeyFormat(final WindowExpression windowExpression) {
    if (ksqlConfig.getBoolean(KsqlConfig.KSQL_WINDOWED_SESSION_KEY_LEGACY_CONFIG)) {
      return KeyFormat.windowed(
          FormatInfo.of(Format.KAFKA),
          WindowInfo.of(
              WindowType.TUMBLING,
              Optional.of(Duration.ofMillis(Long.MAX_VALUE))
          )
      );
    }
    return KeyFormat.windowed(
        FormatInfo.of(Format.KAFKA),
        windowExpression.getKsqlWindowExpression().getWindowInfo()
    );
  }

  private KeySerde<Windowed<Struct>> getKeySerde(final WindowExpression windowExpression) {
    if (ksqlConfig.getBoolean(KsqlConfig.KSQL_WINDOWED_SESSION_KEY_LEGACY_CONFIG)) {
      return keySerde.rebind(WindowInfo.of(
          WindowType.TUMBLING,
          Optional.of(Duration.ofMillis(Long.MAX_VALUE))
      ));
    }

    return keySerde.rebind(windowExpression.getKsqlWindowExpression().getWindowInfo());
  }

  static void throwOnValueFieldCountMismatch(
      final LogicalSchema aggregateSchema,
      final int nonFuncColumnCount,
      final List<FunctionCall> aggregateFunctions
  ) {
    final int totalColumnCount = aggregateFunctions.size() + nonFuncColumnCount;

    final int valueColumnCount = aggregateSchema.value().size();
    if (valueColumnCount != totalColumnCount) {
      throw new IllegalArgumentException(
          "Aggregate schema value field count does not match expected."
          + " expected: " + totalColumnCount
          + ", actual: " + valueColumnCount
          + ", schema: " + aggregateSchema
      );
    }
  }
}
