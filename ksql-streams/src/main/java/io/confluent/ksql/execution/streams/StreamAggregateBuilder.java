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

package io.confluent.ksql.execution.streams;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.function.udaf.window.WindowSelectMapper;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.StreamAggregate;
import io.confluent.ksql.execution.plan.StreamWindowedAggregate;
import io.confluent.ksql.execution.windows.HoppingWindowExpression;
import io.confluent.ksql.execution.windows.KsqlWindowExpression;
import io.confluent.ksql.execution.windows.SessionWindowExpression;
import io.confluent.ksql.execution.windows.TumblingWindowExpression;
import io.confluent.ksql.execution.windows.WindowVisitor;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.serde.KeySerde;
import java.time.Duration;
import java.util.Objects;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueStore;

public final class StreamAggregateBuilder {
  private StreamAggregateBuilder() {
  }

  public static KTable<Struct, GenericRow> build(
      final KGroupedStream<Struct, GenericRow> groupedStream,
      final StreamAggregate aggregate,
      final KsqlQueryBuilder queryBuilder,
      final MaterializedFactory materializedFactory) {
    return build(groupedStream, aggregate, queryBuilder, materializedFactory, AggregateParams::new);
  }

  static KTable<Struct, GenericRow> build(
      final KGroupedStream<Struct, GenericRow> kgroupedStream,
      final StreamAggregate aggregate,
      final KsqlQueryBuilder queryBuilder,
      final MaterializedFactory materializedFactory,
      final AggregateParams.Factory aggregateParamsFactory) {
    final LogicalSchema sourceSchema = aggregate.getSources().get(0).getSchema();
    final int nonFuncColumns = aggregate.getNonFuncColumnCount();
    final AggregateParams aggregateParams = aggregateParamsFactory.create(
        sourceSchema,
        nonFuncColumns,
        queryBuilder.getFunctionRegistry(),
        aggregate.getAggregations()
    );
    final Materialized<Struct, GenericRow, KeyValueStore<Bytes, byte[]>> materialized =
        AggregateBuilderUtils.buildMaterialized(
            aggregate.getProperties().getQueryContext(),
            aggregate.getAggregationSchema(),
            aggregate.getFormats(),
            queryBuilder,
            materializedFactory
        );
    final KTable<Struct, GenericRow> aggregated = kgroupedStream.aggregate(
        aggregateParams.getInitializer(),
        aggregateParams.getAggregator(),
        materialized
    );
    return aggregated.mapValues(aggregateParams.getAggregator().getResultMapper());
  }

  public static KTable<Windowed<Struct>, GenericRow> build(
      final KGroupedStream<Struct, GenericRow> groupedStream,
      final StreamWindowedAggregate aggregate,
      final KsqlQueryBuilder queryBuilder,
      final MaterializedFactory materializedFactory
  ) {
    return build(groupedStream, aggregate, queryBuilder, materializedFactory, AggregateParams::new);
  }

  static KTable<Windowed<Struct>, GenericRow> build(
      final KGroupedStream<Struct, GenericRow> groupedStream,
      final StreamWindowedAggregate aggregate,
      final KsqlQueryBuilder queryBuilder,
      final MaterializedFactory materializedFactory,
      final AggregateParams.Factory aggregateParamsFactory
  ) {
    final LogicalSchema sourceSchema = aggregate.getSources().get(0).getSchema();
    final int nonFuncColumns = aggregate.getNonFuncColumnCount();
    final AggregateParams aggregateParams = aggregateParamsFactory.create(
        sourceSchema,
        nonFuncColumns,
        queryBuilder.getFunctionRegistry(),
        aggregate.getAggregations()
    );
    final KsqlWindowExpression ksqlWindowExpression = aggregate.getWindowExpression();
    final KTable<Windowed<Struct>, GenericRow> aggregated = ksqlWindowExpression.accept(
        new WindowedAggregator(
            groupedStream,
            aggregate,
            queryBuilder,
            materializedFactory,
            aggregateParams
        ),
        null
    );
    final KTable<Windowed<Struct>, GenericRow> reduced = aggregated.mapValues(
        aggregateParams.getAggregator().getResultMapper()
    );
    final WindowSelectMapper windowSelectMapper = aggregateParams.getWindowSelectMapper();
    if (!windowSelectMapper.hasSelects()) {
      return reduced;
    }
    return reduced.mapValues(windowSelectMapper);
  }

  private static class WindowedAggregator
      implements WindowVisitor<KTable<Windowed<Struct>, GenericRow>, Void> {
    final QueryContext queryContext;
    final Formats formats;
    final KGroupedStream<Struct, GenericRow> groupedStream;
    final KsqlQueryBuilder queryBuilder;
    final MaterializedFactory materializedFactory;
    final KeySerde<Struct> keySerde;
    final Serde<GenericRow> valueSerde;
    final AggregateParams aggregateParams;

    WindowedAggregator(
        final KGroupedStream<Struct, GenericRow> groupedStream,
        final StreamWindowedAggregate aggregate,
        final KsqlQueryBuilder queryBuilder,
        final MaterializedFactory materializedFactory,
        final AggregateParams aggregateParams) {
      Objects.requireNonNull(aggregate, "aggregate");
      this.groupedStream = Objects.requireNonNull(groupedStream, "groupedStream");
      this.queryBuilder = Objects.requireNonNull(queryBuilder, "queryBuilder");
      this.materializedFactory = Objects.requireNonNull(materializedFactory, "materializedFactory");
      this.aggregateParams = Objects.requireNonNull(aggregateParams, "aggregateParams");
      this.queryContext = aggregate.getProperties().getQueryContext();
      this.formats = aggregate.getFormats();
      final PhysicalSchema physicalSchema = PhysicalSchema.from(
          aggregate.getAggregationSchema(),
          formats.getOptions()
      );
      keySerde = queryBuilder.buildKeySerde(
          formats.getKeyFormat().getFormatInfo(),
          physicalSchema,
          queryContext
      );
      valueSerde = queryBuilder.buildValueSerde(
          formats.getValueFormat().getFormatInfo(),
          physicalSchema,
          queryContext
      );
    }

    @Override
    public KTable<Windowed<Struct>, GenericRow>  visitHoppingWindowExpression(
        final HoppingWindowExpression window,
        final Void ctx) {
      final TimeWindows windows = TimeWindows
          .of(Duration.ofMillis(window.getSizeUnit().toMillis(window.getSize())))
          .advanceBy(
              Duration.ofMillis(window.getAdvanceByUnit().toMillis(window.getAdvanceBy()))
          );

      return groupedStream
          .windowedBy(windows)
          .aggregate(
              aggregateParams.getInitializer(),
              aggregateParams.getAggregator(),
              materializedFactory.create(
                  keySerde, valueSerde, StreamsUtil.buildOpName(queryContext))
          );
    }

    @Override
    public KTable<Windowed<Struct>, GenericRow>  visitSessionWindowExpression(
        final SessionWindowExpression window,
        final Void ctx) {
      final SessionWindows windows = SessionWindows.with(
          Duration.ofMillis(window.getSizeUnit().toMillis(window.getGap()))
      );
      return groupedStream
          .windowedBy(windows)
          .aggregate(
              aggregateParams.getInitializer(),
              aggregateParams.getAggregator(),
              aggregateParams.getAggregator().getMerger(),
              materializedFactory.create(
                  keySerde, valueSerde, StreamsUtil.buildOpName(queryContext))
          );
    }

    @Override
    public KTable<Windowed<Struct>, GenericRow> visitTumblingWindowExpression(
        final TumblingWindowExpression window,
        final Void ctx) {
      final TimeWindows windows = TimeWindows.of(
          Duration.ofMillis(window.getSizeUnit().toMillis(window.getSize())));
      return groupedStream
          .windowedBy(windows)
          .aggregate(
              aggregateParams.getInitializer(),
              aggregateParams.getAggregator(),
              materializedFactory.create(
                  keySerde, valueSerde, StreamsUtil.buildOpName(queryContext))
          );
    }
  }
}
