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

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.JoinType;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.plan.TableFilter;
import io.confluent.ksql.execution.plan.TableGroupBy;
import io.confluent.ksql.execution.plan.TableMapValues;
import io.confluent.ksql.execution.plan.TableSink;
import io.confluent.ksql.execution.plan.TableTableJoin;
import io.confluent.ksql.execution.streams.ExecutionStepFactory;
import io.confluent.ksql.execution.streams.TableFilterBuilder;
import io.confluent.ksql.execution.streams.TableGroupByBuilder;
import io.confluent.ksql.execution.streams.TableMapValuesBuilder;
import io.confluent.ksql.execution.streams.TableSinkBuilder;
import io.confluent.ksql.execution.streams.TableTableJoinBuilder;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KeyField.LegacyField;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.KeySerde;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.streams.StreamsFactories;
import io.confluent.ksql.util.KsqlConfig;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public class SchemaKTable<K> extends SchemaKStream<K> {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  private final KTable<K, GenericRow> ktable;
  private final ExecutionStep<KTable<K, GenericRow>> sourceTableStep;

  public SchemaKTable(
      final KTable<K, GenericRow> ktable,
      final ExecutionStep<KTable<K, GenericRow>> sourceTableStep,
      final KeyFormat keyFormat,
      final KeySerde<K> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final Type type,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry
  ) {
    this(
        ktable,
        sourceTableStep,
        keyFormat,
        keySerde,
        keyField,
        sourceSchemaKStreams,
        type,
        ksqlConfig,
        functionRegistry,
        StreamsFactories.create(ksqlConfig)
    );
  }

  SchemaKTable(
      final KTable<K, GenericRow> ktable,
      final ExecutionStep<KTable<K, GenericRow>> sourceTableStep,
      final KeyFormat keyFormat,
      final KeySerde<K> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final Type type,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final StreamsFactories streamsFactories
  ) {
    super(
        null,
        null,
        Objects.requireNonNull(sourceTableStep, "sourceTableStep").getProperties(),
        keyFormat,
        keySerde,
        keyField,
        sourceSchemaKStreams,
        type,
        ksqlConfig,
        functionRegistry,
        streamsFactories
    );
    this.ktable = ktable;
    this.sourceTableStep = sourceTableStep;
  }

  @Override
  @SuppressWarnings("unchecked")
  public SchemaKTable<K> into(
      final String kafkaTopicName,
      final LogicalSchema outputSchema,
      final ValueFormat valueFormat,
      final Set<SerdeOption> options,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder builder
  ) {
    final TableSink<K> step = ExecutionStepFactory.tableSink(
        contextStacker,
        outputSchema,
        sourceTableStep,
        Formats.of(keyFormat, valueFormat, options),
        kafkaTopicName
    );
    TableSinkBuilder.build(
        ktable,
        step,
        (fmt, schema, ctx) -> keySerde,
        builder
    );
    return new SchemaKTable<>(
        ktable,
        step,
        keyFormat,
        keySerde,
        keyField,
        sourceSchemaKStreams,
        type,
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked")
  @Override
  public SchemaKTable<K> filter(
      final Expression filterExpression,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final TableFilter<KTable<K, GenericRow>> step = ExecutionStepFactory.tableFilter(
        contextStacker,
        sourceTableStep,
        rewriteTimeComparisonForFilter(filterExpression)
    );
    return new SchemaKTable<>(
        TableFilterBuilder.build(ktable, step, queryBuilder),
        step,
        keyFormat,
        keySerde,
        keyField,
        Collections.singletonList(this),
        Type.FILTER,
        ksqlConfig,
        functionRegistry
    );
  }

  @Override
  public SchemaKTable<K> select(
      final List<SelectExpression> selectExpressions,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder ksqlQueryBuilder) {
    final KeySelection selection = new KeySelection(
        selectExpressions
    );
    final TableMapValues<KTable<K, GenericRow>> step = ExecutionStepFactory.tableMapValues(
        contextStacker,
        sourceTableStep,
        selectExpressions,
        ksqlQueryBuilder
    );
    return new SchemaKTable<>(
        TableMapValuesBuilder.build(ktable, step, ksqlQueryBuilder),
        step,
        keyFormat,
        keySerde,
        selection.getKey(),
        Collections.singletonList(this),
        Type.PROJECT,
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked") // needs investigating
  @Override
  public KStream getKstream() {
    return ktable.toStream();
  }

  public KTable<K, GenericRow> getKtable() {
    return ktable;
  }

  public ExecutionStep<KTable<K, GenericRow>> getSourceTableStep() {
    return sourceTableStep;
  }

  @Override
  @SuppressWarnings("unchecked")
  public SchemaKGroupedStream groupBy(
      final ValueFormat valueFormat,
      final List<Expression> groupByExpressions,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {

    final KeyFormat groupedKeyFormat = KeyFormat.nonWindowed(keyFormat.getFormatInfo());

    final KeySerde<Struct> groupedKeySerde = keySerde
        .rebind(StructKeyUtil.ROWKEY_SERIALIZED_SCHEMA);

    final ColumnName aggregateKeyName = groupedKeyNameFor(groupByExpressions);
    final LegacyField legacyKeyField = LegacyField.notInSchema(aggregateKeyName, SqlTypes.STRING);
    final Optional<ColumnName> newKeyField =
        getSchema().findValueColumn(aggregateKeyName).map(Column::fullName).map(ColumnName::of);

    final TableGroupBy<K> step = ExecutionStepFactory.tableGroupBy(
        contextStacker,
        sourceTableStep,
        Formats.of(groupedKeyFormat, valueFormat, SerdeOption.none()),
        groupByExpressions
    );
    return new SchemaKGroupedTable(
        TableGroupByBuilder.build(
            ktable,
            step,
            queryBuilder,
            streamsFactories.getGroupedFactory()
        ),
        step,
        groupedKeyFormat,
        groupedKeySerde,
        KeyField.of(newKeyField, Optional.of(legacyKeyField)),
        Collections.singletonList(this),
        ksqlConfig,
        functionRegistry);
  }

  public SchemaKTable<K> join(
      final SchemaKTable<K> schemaKTable,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final QueryContext.Stacker contextStacker
  ) {
    final TableTableJoin<K> step = ExecutionStepFactory.tableTableJoin(
        contextStacker,
        JoinType.INNER,
        sourceTableStep,
        schemaKTable.getSourceTableStep(),
        joinSchema
    );
    return new SchemaKTable<>(
        TableTableJoinBuilder.build(ktable, schemaKTable.ktable, step),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, schemaKTable),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKTable<K> leftJoin(
      final SchemaKTable<K> schemaKTable,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final QueryContext.Stacker contextStacker
  ) {
    final TableTableJoin<K> step = ExecutionStepFactory.tableTableJoin(
        contextStacker,
        JoinType.LEFT,
        sourceTableStep,
        schemaKTable.getSourceTableStep(),
        joinSchema
    );
    return new SchemaKTable<>(
        TableTableJoinBuilder.build(ktable, schemaKTable.ktable, step),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, schemaKTable),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKTable<K> outerJoin(
      final SchemaKTable<K> schemaKTable,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final QueryContext.Stacker contextStacker
  ) {
    final TableTableJoin<K> step = ExecutionStepFactory.tableTableJoin(
        contextStacker,
        JoinType.OUTER,
        sourceTableStep,
        schemaKTable.getSourceTableStep(),
        joinSchema
    );
    return new SchemaKTable<>(
        TableTableJoinBuilder.build(ktable, schemaKTable.ktable, step),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, schemaKTable),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }
}
