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

package io.confluent.ksql.materialization.ks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.testing.NullPointerTester;
import com.google.common.testing.NullPointerTester.Visibility;
import io.confluent.ksql.materialization.ks.KsMaterializationFactory.LocatorFactory;
import io.confluent.ksql.materialization.ks.KsMaterializationFactory.MaterializationFactory;
import io.confluent.ksql.materialization.ks.KsMaterializationFactory.StateStoreFactory;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KsMaterializationFactoryTest {

  private static final String STORE_NAME = "someStore";
  private static final URL DEFAULT_APP_SERVER = buildDefaultAppServer();

  private static final LogicalSchema SCHEMA = LogicalSchema.builder()
      .keyColumn(ColumnName.of("k0"), SqlTypes.STRING)
      .valueColumn(ColumnName.of("v0"), SqlTypes.DOUBLE)
      .build();

  @Mock
  private KafkaStreams kafkaStreams;
  @Mock
  private Serializer<Struct> keySerializer;

  @Mock
  private LocatorFactory locatorFactory;
  @Mock
  private KsLocator locator;
  @Mock
  private StateStoreFactory storeFactory;
  @Mock
  private KsStateStore stateStore;
  @Mock
  private MaterializationFactory materializationFactory;
  @Mock
  private KsMaterialization materialization;
  private KsMaterializationFactory factory;
  private final Map<String, Object> streamsProperties = new HashMap<>();

  @Before
  public void setUp() {
    factory = new KsMaterializationFactory(
        locatorFactory,
        storeFactory,
        materializationFactory
    );

    when(locatorFactory.create(any(), any(), any(), any())).thenReturn(locator);
    when(storeFactory.create(any(), any(), any())).thenReturn(stateStore);
    when(materializationFactory.create(any(), any(), any())).thenReturn(materialization);

    streamsProperties.clear();
    streamsProperties.put(StreamsConfig.APPLICATION_SERVER_CONFIG, DEFAULT_APP_SERVER.toString());
  }

  @Test
  public void shouldThrowNPEs() {
    new NullPointerTester()
        .setDefault(LocatorFactory.class, locatorFactory)
        .setDefault(StateStoreFactory.class, storeFactory)
        .setDefault(MaterializationFactory.class, materializationFactory)
        .testConstructors(KsMaterializationFactory.class, Visibility.PACKAGE);
  }

  @Test
  public void shouldReturnEmptyIfAppServerNotConfigured() {
    // Given:
    streamsProperties.remove(StreamsConfig.APPLICATION_SERVER_CONFIG);

    // When:
    final Optional<KsMaterialization> result = factory
        .create(STORE_NAME, kafkaStreams, SCHEMA, keySerializer, Optional.empty(), streamsProperties);

    // Then:
    assertThat(result, is(Optional.empty()));
  }

  @Test
  public void shouldBuildLocatorWithCorrectParams() {
    // When:
    factory.create(STORE_NAME, kafkaStreams, SCHEMA, keySerializer, Optional.empty(), streamsProperties);

    // Then:
    verify(locatorFactory).create(
        STORE_NAME,
        kafkaStreams,
        keySerializer,
        DEFAULT_APP_SERVER
    );
  }

  @Test
  public void shouldBuildStateStoreWithCorrectParams() {
    // When:
    factory.create(STORE_NAME, kafkaStreams, SCHEMA, keySerializer, Optional.empty(), streamsProperties);

    // Then:
    verify(storeFactory).create(
        STORE_NAME,
        kafkaStreams,
        SCHEMA
    );
  }

  @Test
  public void shouldBuildMaterializationWithCorrectParams() {
    // Given:
    final Optional<WindowType> windowType = Optional.of(WindowType.SESSION);

    // When:
    factory.create(STORE_NAME, kafkaStreams, SCHEMA, keySerializer, windowType, streamsProperties);

    // Then:
    verify(materializationFactory).create(
        windowType,
        locator,
        stateStore
    );
  }

  @Test
  public void shouldReturnMaterialization() {
    // When:
    final Optional<KsMaterialization> result = factory
        .create(STORE_NAME, kafkaStreams, SCHEMA, keySerializer, Optional.empty(), streamsProperties);

    // Then:
    assertThat(result,  is(Optional.of(materialization)));
  }

  private static URL buildDefaultAppServer() {
    try {
      return new URL("https://someHost:9876");
    } catch (final MalformedURLException e) {
      throw new AssertionError("Failed to build app server URL");
    }
  }
}