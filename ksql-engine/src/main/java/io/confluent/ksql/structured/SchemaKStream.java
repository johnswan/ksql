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

import static io.confluent.ksql.execution.streams.ExecutionStepFactory.streamSource;
import static io.confluent.ksql.execution.streams.ExecutionStepFactory.streamSourceWindowed;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.engine.rewrite.StatementRewriteForRowtime;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.context.QueryLoggerUtil;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.expression.tree.ColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.ExecutionStepProperties;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.JoinType;
import io.confluent.ksql.execution.plan.LogicalSchemaWithMetaAndKeyFields;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.plan.StreamFilter;
import io.confluent.ksql.execution.plan.StreamGroupBy;
import io.confluent.ksql.execution.plan.StreamGroupByKey;
import io.confluent.ksql.execution.plan.StreamMapValues;
import io.confluent.ksql.execution.plan.StreamSelectKey;
import io.confluent.ksql.execution.plan.StreamSink;
import io.confluent.ksql.execution.plan.StreamSource;
import io.confluent.ksql.execution.plan.StreamStreamJoin;
import io.confluent.ksql.execution.plan.StreamTableJoin;
import io.confluent.ksql.execution.plan.StreamToTable;
import io.confluent.ksql.execution.streams.ExecutionStepFactory;
import io.confluent.ksql.execution.streams.StreamFilterBuilder;
import io.confluent.ksql.execution.streams.StreamGroupByBuilder;
import io.confluent.ksql.execution.streams.StreamMapValuesBuilder;
import io.confluent.ksql.execution.streams.StreamSelectKeyBuilder;
import io.confluent.ksql.execution.streams.StreamSinkBuilder;
import io.confluent.ksql.execution.streams.StreamSourceBuilder;
import io.confluent.ksql.execution.streams.StreamStreamJoinBuilder;
import io.confluent.ksql.execution.streams.StreamTableJoinBuilder;
import io.confluent.ksql.execution.streams.StreamToTableBuilder;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KeyField.LegacyField;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.FormatOptions;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.KeySerde;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.streams.StreamsFactories;
import io.confluent.ksql.util.IdentifierUtil;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.Topology.AutoOffsetReset;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Windowed;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SchemaKStream<K> {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  private static final FormatOptions FORMAT_OPTIONS =
      FormatOptions.of(IdentifierUtil::needsQuotes);

  static final String GROUP_BY_COLUMN_SEPARATOR = "|+|";

  public enum Type { SOURCE, PROJECT, FILTER, AGGREGATE, SINK, REKEY, JOIN }

  final KStream<K, GenericRow> kstream;
  final KeyFormat keyFormat;
  final KeySerde<K> keySerde;
  final KeyField keyField;
  final List<SchemaKStream> sourceSchemaKStreams;
  final Type type;
  final KsqlConfig ksqlConfig;
  final FunctionRegistry functionRegistry;
  final StreamsFactories streamsFactories;
  private final ExecutionStep<KStream<K, GenericRow>> sourceStep;
  private final ExecutionStepProperties sourceProperties;

  private static <K> SchemaKStream<K> forSource(
      final KsqlQueryBuilder builder,
      final KeyFormat keyFormat,
      final KeySerde<K> keySerde,
      final StreamSource<KStream<K, GenericRow>> streamSource,
      final KeyField keyField) {
    final KStream<K, GenericRow> kstream = streamSource.build(builder);
    return new SchemaKStream<>(
        kstream,
        streamSource,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(),
        SchemaKStream.Type.SOURCE,
        builder.getKsqlConfig(),
        builder.getFunctionRegistry()
    );
  }

  public static SchemaKStream<?> forSource(
      final KsqlQueryBuilder builder,
      final DataSource<?> dataSource,
      final LogicalSchemaWithMetaAndKeyFields schemaWithMetaAndKeyFields,
      final QueryContext.Stacker contextStacker,
      final int timestampIndex,
      final Optional<AutoOffsetReset> offsetReset,
      final KeyField keyField
  ) {
    final KsqlTopic topic = dataSource.getKsqlTopic();
    if (topic.getKeyFormat().isWindowed()) {
      final StreamSource<KStream<Windowed<Struct>, GenericRow>> step = streamSourceWindowed(
          contextStacker,
          schemaWithMetaAndKeyFields,
          topic.getKafkaTopicName(),
          Formats.of(topic.getKeyFormat(), topic.getValueFormat(), dataSource.getSerdeOptions()),
          dataSource.getTimestampExtractionPolicy(),
          timestampIndex,
          offsetReset
      );
      return forSource(
          builder,
          topic.getKeyFormat(),
          StreamSourceBuilder.getWindowedKeySerde(builder, step),
          step,
          keyField);
    } else {
      final StreamSource<KStream<Struct, GenericRow>> step = streamSource(
          contextStacker,
          schemaWithMetaAndKeyFields,
          topic.getKafkaTopicName(),
          Formats.of(topic.getKeyFormat(), topic.getValueFormat(), dataSource.getSerdeOptions()),
          dataSource.getTimestampExtractionPolicy(),
          timestampIndex,
          offsetReset
      );
      return forSource(
          builder,
          topic.getKeyFormat(),
          StreamSourceBuilder.getKeySerde(builder, step),
          step,
          keyField);
    }
  }

  @VisibleForTesting
  SchemaKStream(
      final KStream<K, GenericRow> kstream,
      final ExecutionStep<KStream<K, GenericRow>> sourceStep,
      final KeyFormat keyFormat,
      final KeySerde<K> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final Type type,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry
  ) {
    this(
        kstream,
        requireNonNull(sourceStep, "sourceStep"),
        sourceStep.getProperties(),
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

  // CHECKSTYLE_RULES.OFF: ParameterNumber
  SchemaKStream(
      final KStream<K, GenericRow> kstream,
      final ExecutionStep<KStream<K, GenericRow>> sourceStep,
      final ExecutionStepProperties sourceProperties,
      final KeyFormat keyFormat,
      final KeySerde<K> keySerde,
      final KeyField keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final Type type,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final StreamsFactories streamsFactories
  ) {
    // CHECKSTYLE_RULES.ON: ParameterNumber
    this.keyFormat = requireNonNull(keyFormat, "keyFormat");
    this.keySerde = requireNonNull(keySerde, "keySerde");
    this.sourceStep = sourceStep;
    this.sourceProperties = Objects.requireNonNull(sourceProperties, "sourceProperties");
    this.kstream = kstream;
    this.keyField = requireNonNull(keyField, "keyField").validateKeyExistsIn(getSchema());
    this.sourceSchemaKStreams = requireNonNull(sourceSchemaKStreams, "sourceSchemaKStreams");
    this.type = requireNonNull(type, "type");
    this.ksqlConfig = requireNonNull(ksqlConfig, "ksqlConfig");
    this.functionRegistry = requireNonNull(functionRegistry, "functionRegistry");
    this.streamsFactories = requireNonNull(streamsFactories);
  }

  @SuppressWarnings("unchecked")
  public SchemaKTable<K> toTable(
      final KeyFormat keyFormat,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final StreamToTable<KStream<K, GenericRow>, KTable<K, GenericRow>> step =
        ExecutionStepFactory.streamToTable(
            contextStacker,
            Formats.of(keyFormat, valueFormat, Collections.emptySet()),
            sourceStep
        );
    return new SchemaKTable<>(
        StreamToTableBuilder.build(
            (KStream) kstream,
            (StreamToTable) step,
            queryBuilder,
            streamsFactories.getMaterializedFactory()
        ),
        step,
        keyFormat,
        keySerde,
        keyField,
        Collections.singletonList(this),
        type,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKStream<K> withKeyField(final KeyField resultKeyField) {
    return new SchemaKStream<>(
        kstream,
        sourceStep,
        keyFormat,
        keySerde,
        resultKeyField,
        sourceSchemaKStreams,
        type,
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> into(
      final String kafkaTopicName,
      final LogicalSchema outputSchema,
      final ValueFormat valueFormat,
      final Set<SerdeOption> options,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final StreamSink<K> step = ExecutionStepFactory.streamSink(
        contextStacker,
        outputSchema,
        Formats.of(keyFormat, valueFormat, options),
        sourceStep,
        kafkaTopicName
    );
    StreamSinkBuilder.build(
        kstream,
        step,
        (fmt, schema, ctx) -> keySerde,
        queryBuilder
    );
    return new SchemaKStream<>(
        kstream,
        step,
        keyFormat,
        keySerde,
        keyField,
        Collections.singletonList(this),
        SchemaKStream.Type.SINK,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKStream<K> filter(
      final Expression filterExpression,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final StreamFilter<KStream<K, GenericRow>> step = ExecutionStepFactory.streamFilter(
        contextStacker,
        sourceStep,
        rewriteTimeComparisonForFilter(filterExpression)
    );
    return new SchemaKStream<>(
        StreamFilterBuilder.build(kstream, step, queryBuilder),
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

  static Expression rewriteTimeComparisonForFilter(final Expression expression) {
    return new StatementRewriteForRowtime()
        .rewriteForRowtime(expression);
  }

  public SchemaKStream<K> select(
      final List<SelectExpression> selectExpressions,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder ksqlQueryBuilder) {
    final KeySelection selection = new KeySelection(selectExpressions);
    final StreamMapValues<KStream<K, GenericRow>> step = ExecutionStepFactory.streamMapValues(
        contextStacker,
        sourceStep,
        selectExpressions,
        ksqlQueryBuilder
    );
    return new SchemaKStream<>(
        StreamMapValuesBuilder.build(kstream, step, ksqlQueryBuilder),
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

  class KeySelection {

    private final KeyField key;

    KeySelection(final List<SelectExpression> selectExpressions) {
      this.key = findKeyField(selectExpressions);
    }

    private KeyField findKeyField(final List<SelectExpression> selectExpressions) {
      return KeyField.of(findNewKeyField(selectExpressions), findLegacyKeyField(selectExpressions));
    }

    private Optional<ColumnName> findNewKeyField(final List<SelectExpression> selectExpressions) {
      if (!getKeyField().name().isPresent()) {
        return Optional.empty();
      }

      final ColumnName fullFieldName = getKeyField().name().get();
      final Optional<String> fieldAlias = SchemaUtil.getFieldNameAlias(fullFieldName.name());
      final String fieldNameWithNoAlias = SchemaUtil.getFieldNameWithNoAlias(fullFieldName.name());
      final Column keyColumn = Column.of(
          fieldAlias.map(SourceName::of),
          ColumnName.of(fieldNameWithNoAlias),
          SqlTypes.STRING
      );

      return doFindKeyColumn(selectExpressions, keyColumn)
          .map(Column::fullName)
          .map(ColumnName::of);
    }

    private Optional<LegacyField> findLegacyKeyField(
        final List<SelectExpression> selectExpressions
    ) {
      if (!getKeyField().legacy().isPresent()) {
        return Optional.empty();
      }

      final LegacyField keyField = getKeyField().legacy().get();
      if (keyField.isNotInSchema()) {
        // The key "field" isn't an actual field in the schema
        return Optional.of(keyField);
      }

      return doFindKeyColumn(selectExpressions, Column.of(keyField.name(), keyField.type()))
          .map(field -> LegacyField.of(ColumnName.of(field.fullName()), field.type()));
    }

    private Optional<Column> doFindKeyColumn(
        final List<SelectExpression> selectExpressions,
        final Column keyField
    ) {
      Optional<Column> found = Optional.empty();

      for (int i = 0; i < selectExpressions.size(); i++) {
        final ColumnName toName = selectExpressions.get(i).getName();
        final Expression toExpression = selectExpressions.get(i).getExpression();

        if (toExpression instanceof ColumnReferenceExp) {
          final ColumnReferenceExp nameRef
              = (ColumnReferenceExp) toExpression;

          if (SchemaUtil.isFieldName(nameRef.getReference().toString(), keyField.fullName())) {
            found = Optional.of(Column.of(toName, keyField.type()));
            break;
          }
        }
      }

      return found
          .filter(f -> !SchemaUtil.isFieldName(f.name().name(), SchemaUtil.ROWTIME_NAME.name()))
          .filter(f -> !SchemaUtil.isFieldName(f.name().name(), SchemaUtil.ROWKEY_NAME.name()));
    }

    public KeyField getKey() {
      return key;
    }
  }

  public SchemaKStream<K> leftJoin(
      final SchemaKTable<K> schemaKTable,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final StreamTableJoin<K> step = ExecutionStepFactory.streamTableJoin(
        contextStacker,
        JoinType.LEFT,
        Formats.of(keyFormat, valueFormat, SerdeOption.none()),
        sourceStep,
        schemaKTable.getSourceTableStep(),
        joinSchema
    );
    return new SchemaKStream<>(
        StreamTableJoinBuilder.build(
            kstream,
            schemaKTable.getKtable(),
            step,
            (fmt, schema, qctx) -> keySerde,
            queryBuilder,
            streamsFactories.getJoinedFactory()
        ),
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

  public SchemaKStream<K> leftJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final JoinWindows joinWindows,
      final ValueFormat leftFormat,
      final ValueFormat rightFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder) {

    final StreamStreamJoin<K> step = ExecutionStepFactory.streamStreamJoin(
        contextStacker,
        JoinType.LEFT,
        Formats.of(keyFormat, leftFormat, SerdeOption.none()),
        Formats.of(keyFormat, rightFormat, SerdeOption.none()),
        sourceStep,
        otherSchemaKStream.sourceStep,
        joinSchema,
        joinWindows
    );
    return new SchemaKStream<>(
        StreamStreamJoinBuilder.build(
            kstream,
            otherSchemaKStream.kstream,
            step,
            (fmt, schema, qctx) -> keySerde,
            queryBuilder,
            streamsFactories.getJoinedFactory()
        ),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, otherSchemaKStream),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKStream<K> join(
      final SchemaKTable<K> schemaKTable,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final StreamTableJoin<K> step = ExecutionStepFactory.streamTableJoin(
        contextStacker,
        JoinType.INNER,
        Formats.of(keyFormat, valueFormat, SerdeOption.none()),
        sourceStep,
        schemaKTable.getSourceTableStep(),
        joinSchema
    );
    return new SchemaKStream<>(
        StreamTableJoinBuilder.build(
            kstream,
            schemaKTable.getKtable(),
            step,
            (fmt, schema, qctx) -> keySerde,
            queryBuilder,
            streamsFactories.getJoinedFactory()
        ),
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

  public SchemaKStream<K> join(
      final SchemaKStream<K> otherSchemaKStream,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final JoinWindows joinWindows,
      final ValueFormat leftFormat,
      final ValueFormat rightFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder) {
    final StreamStreamJoin<K> step = ExecutionStepFactory.streamStreamJoin(
        contextStacker,
        JoinType.INNER,
        Formats.of(keyFormat, leftFormat, SerdeOption.none()),
        Formats.of(keyFormat, rightFormat, SerdeOption.none()),
        sourceStep,
        otherSchemaKStream.sourceStep,
        joinSchema,
        joinWindows
    );
    return new SchemaKStream<>(
        StreamStreamJoinBuilder.build(
            kstream,
            otherSchemaKStream.kstream,
            step,
            (fmt, schema, qctx) -> keySerde,
            queryBuilder,
            streamsFactories.getJoinedFactory()
        ),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, otherSchemaKStream),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }

  public SchemaKStream<K> outerJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final LogicalSchema joinSchema,
      final KeyField keyField,
      final JoinWindows joinWindows,
      final ValueFormat leftFormat,
      final ValueFormat rightFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder) {
    final StreamStreamJoin<K> step = ExecutionStepFactory.streamStreamJoin(
        contextStacker,
        JoinType.OUTER,
        Formats.of(keyFormat, leftFormat, SerdeOption.none()),
        Formats.of(keyFormat, rightFormat, SerdeOption.none()),
        sourceStep,
        otherSchemaKStream.sourceStep,
        joinSchema,
        joinWindows
    );
    return new SchemaKStream<>(
        StreamStreamJoinBuilder.build(
            kstream,
            otherSchemaKStream.kstream,
            step,
            (fmt, schema, qctx) -> keySerde,
            queryBuilder,
            streamsFactories.getJoinedFactory()
        ),
        step,
        keyFormat,
        keySerde,
        keyField,
        ImmutableList.of(this, otherSchemaKStream),
        Type.JOIN,
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<Struct> selectKey(
      final ColumnName fieldName,
      final boolean updateRowKey,
      final QueryContext.Stacker contextStacker
  ) {
    if (keySerde.isWindowed()) {
      throw new UnsupportedOperationException("Can not selectKey of windowed stream");
    }

    final Optional<Column> existingKey = keyField.resolve(getSchema(), ksqlConfig);

    final Column proposedKey = getSchema().findValueColumn(fieldName)
        .orElseThrow(IllegalArgumentException::new);

    final LegacyField proposedLegacy = LegacyField.of(
        ColumnName.of(proposedKey.fullName()), proposedKey.type());

    final KeyField resultantKeyField = isRowKey(fieldName)
            ? keyField.withLegacy(proposedLegacy)
            : KeyField.of(fieldName, proposedLegacy);

    final boolean namesMatch = existingKey
        .map(kf -> SchemaUtil.isFieldName(proposedKey.fullName(), kf.fullName()))
        .orElse(false);

    // Note: Prior to v5.3 a selectKey(ROWKEY) would result in a repartition.
    // To maintain compatibility, old queries, started prior to v5.3, must have repartition step.
    // So we only handle rowkey for new queries:
    final boolean treatAsRowKey = usingNewKeyFields() && isRowKey(proposedKey.name());

    if (namesMatch || treatAsRowKey) {
      return (SchemaKStream<Struct>) new SchemaKStream<>(
          kstream,
          sourceStep,
          keyFormat,
          keySerde,
          resultantKeyField,
          sourceSchemaKStreams,
          type,
          ksqlConfig,
          functionRegistry
      );
    }

    final KeyField newKeyField = getSchema().isMetaColumn(fieldName)
        ? resultantKeyField.withName(Optional.empty())
        : resultantKeyField;

    final KeySerde<Struct> selectKeySerde = keySerde.rebind(StructKeyUtil.ROWKEY_SERIALIZED_SCHEMA);
    final StreamSelectKey<K> step = ExecutionStepFactory.streamSelectKey(
        contextStacker,
        sourceStep,
        fieldName,
        updateRowKey
    );
    return new SchemaKStream<>(
        StreamSelectKeyBuilder.build(kstream, step),
        step,
        keyFormat,
        selectKeySerde,
        newKeyField,
        Collections.singletonList(this),
        Type.REKEY,
        ksqlConfig,
        functionRegistry
    );
  }

  private boolean usingNewKeyFields() {
    return !ksqlConfig.getBoolean(KsqlConfig.KSQL_USE_LEGACY_KEY_FIELD);
  }

  private static boolean isRowKey(final ColumnName fieldName) {
    return SchemaUtil.isFieldName(fieldName.name(), SchemaUtil.ROWKEY_NAME.name());
  }

  private Object extractColumn(final int keyIndexInValue, final GenericRow value) {
    if (value.getColumns().size() != getSchema().value().size()) {
      throw new IllegalStateException("Field count mismatch. "
          + "Schema fields: " + getSchema()
          + ", row:" + value);
    }

    return value
        .getColumns()
        .get(keyIndexInValue);
  }

  private static ColumnName fieldNameFromExpression(final Expression expression) {
    if (expression instanceof ColumnReferenceExp) {
      final ColumnReferenceExp nameRef = (ColumnReferenceExp) expression;
      return nameRef.getReference().name();
    }
    return null;
  }

  private boolean rekeyRequired(final List<Expression> groupByExpressions) {
    if (groupByExpressions.size() != 1) {
      return true;
    }

    final ColumnName groupByField = fieldNameFromExpression(groupByExpressions.get(0));
    if (groupByField == null) {
      return true;
    }

    if (groupByField.equals(SchemaUtil.ROWKEY_NAME)) {
      return false;
    }

    final Optional<Column> keyColumn = getKeyField().resolve(getSchema(), ksqlConfig);
    if (!keyColumn.isPresent()) {
      return true;
    }

    final ColumnName keyFieldName = keyColumn.get().name();
    return !groupByField.equals(keyFieldName);
  }

  public SchemaKGroupedStream groupBy(
      final ValueFormat valueFormat,
      final List<Expression> groupByExpressions,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final boolean rekey = rekeyRequired(groupByExpressions);
    final KeyFormat rekeyedKeyFormat = KeyFormat.nonWindowed(keyFormat.getFormatInfo());
    if (!rekey) {
      return groupByKey(rekeyedKeyFormat, valueFormat, contextStacker, queryBuilder);
    }

    final KeySerde<Struct> groupedKeySerde = keySerde
        .rebind(StructKeyUtil.ROWKEY_SERIALIZED_SCHEMA);

    final ColumnName aggregateKeyName = groupedKeyNameFor(groupByExpressions);
    final LegacyField legacyKeyField = LegacyField
        .notInSchema(aggregateKeyName, SqlTypes.STRING);
    final Optional<ColumnName> newKeyCol = getSchema().findValueColumn(aggregateKeyName)
        .map(Column::name);

    final StreamGroupBy<K> source = ExecutionStepFactory.streamGroupBy(
        contextStacker,
        sourceStep,
        Formats.of(rekeyedKeyFormat, valueFormat, SerdeOption.none()),
        groupByExpressions
    );
    return new SchemaKGroupedStream(
        StreamGroupByBuilder.build(
            kstream,
            source,
            queryBuilder,
            streamsFactories.getGroupedFactory()
        ),
        source,
        rekeyedKeyFormat,
        groupedKeySerde,
        KeyField.of(newKeyCol, Optional.of(legacyKeyField)),
        Collections.singletonList(this),
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked")
  private SchemaKGroupedStream groupByKey(
      final KeyFormat rekeyedKeyFormat,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker,
      final KsqlQueryBuilder queryBuilder
  ) {
    final KeySerde<Struct> structKeySerde = getGroupByKeyKeySerde();
    final StreamGroupByKey step =
        ExecutionStepFactory.streamGroupByKey(
            contextStacker,
            (ExecutionStep) sourceStep,
            Formats.of(rekeyedKeyFormat, valueFormat, SerdeOption.none())
        );
    return new SchemaKGroupedStream(
        StreamGroupByBuilder.build(
            (KStream) kstream,
            step,
            queryBuilder,
            streamsFactories.getGroupedFactory()
        ),
        step,
        keyFormat,
        structKeySerde,
        keyField,
        Collections.singletonList(this),
        ksqlConfig,
        functionRegistry
    );
  }

  @SuppressWarnings("unchecked")
  private KeySerde<Struct> getGroupByKeyKeySerde() {
    if (keySerde.isWindowed()) {
      throw new UnsupportedOperationException("Group by on windowed should always require rekey");
    }

    return (KeySerde<Struct>) keySerde;
  }

  ExecutionStep<KStream<K, GenericRow>> getSourceStep() {
    return sourceStep;
  }

  public KeyField getKeyField() {
    return keyField;
  }

  public QueryContext getQueryContext() {
    return sourceProperties.getQueryContext();
  }

  public LogicalSchema getSchema() {
    return sourceProperties.getSchema();
  }

  public KeySerde<K> getKeySerde() {
    return keySerde;
  }

  public KStream<K, GenericRow> getKstream() {
    return kstream;
  }

  public List<SchemaKStream> getSourceSchemaKStreams() {
    return sourceSchemaKStreams;
  }

  public String getExecutionPlan(final String indent) {
    final StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(indent)
        .append(" > [ ")
        .append(type).append(" ] | Schema: ")
        .append(getSchema().toString(FORMAT_OPTIONS))
        .append(" | Logger: ").append(QueryLoggerUtil.queryLoggerName(getQueryContext()))
        .append("\n");
    for (final SchemaKStream schemaKStream : sourceSchemaKStreams) {
      stringBuilder
          .append("\t")
          .append(indent)
          .append(schemaKStream.getExecutionPlan(indent + "\t"));
    }
    return stringBuilder.toString();
  }

  public Type getType() {
    return type;
  }

  public KeyFormat getKeyFormat() {
    return keyFormat;
  }

  public FunctionRegistry getFunctionRegistry() {
    return functionRegistry;
  }

  ColumnName groupedKeyNameFor(final List<Expression> groupByExpressions) {
    return ColumnName.of(groupByExpressions.stream()
        .map(Expression::toString)
        .collect(Collectors.joining(GROUP_BY_COLUMN_SEPARATOR)));
  }
}
