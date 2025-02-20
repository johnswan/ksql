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

package io.confluent.ksql.engine.rewrite;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.NodeLocation;
import io.confluent.ksql.parser.properties.with.CreateSourceAsProperties;
import io.confluent.ksql.parser.properties.with.CreateSourceProperties;
import io.confluent.ksql.parser.tree.AliasedRelation;
import io.confluent.ksql.parser.tree.AstNode;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateStreamAsSelect;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.Explain;
import io.confluent.ksql.parser.tree.GroupBy;
import io.confluent.ksql.parser.tree.GroupingElement;
import io.confluent.ksql.parser.tree.InsertInto;
import io.confluent.ksql.parser.tree.Join;
import io.confluent.ksql.parser.tree.Join.Type;
import io.confluent.ksql.parser.tree.JoinCriteria;
import io.confluent.ksql.execution.windows.KsqlWindowExpression;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Relation;
import io.confluent.ksql.parser.tree.ResultMaterialization;
import io.confluent.ksql.parser.tree.Select;
import io.confluent.ksql.parser.tree.SimpleGroupBy;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.parser.tree.Statements;
import io.confluent.ksql.parser.tree.TableElement;
import io.confluent.ksql.parser.tree.TableElement.Namespace;
import io.confluent.ksql.parser.tree.TableElements;
import io.confluent.ksql.parser.tree.WindowExpression;
import io.confluent.ksql.parser.tree.WithinExpression;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class StatementRewriterTest {

  @Mock
  private BiFunction<Expression, Object, Expression> expressionRewriter;
  @Mock
  private BiFunction<AstNode, Object, AstNode> mockRewriter;
  @Mock
  private Optional<NodeLocation> location;
  @Mock
  private Object context;
  @Mock
  private SourceName sourceName;
  @Mock
  private Select select;
  @Mock
  private Select rewrittenSelect;
  @Mock
  private Relation relation;
  @Mock
  private Relation rewrittenRelation;
  @Mock
  private Expression expression;
  @Mock
  private Expression rewrittenExpression;
  @Mock
  private OptionalInt optionalInt;
  @Mock
  private Relation rightRelation;
  @Mock
  private Relation rewrittenRightRelation;
  @Mock
  private JoinCriteria joinCriteria;
  @Mock
  private CreateSourceProperties sourceProperties;
  @Mock
  private Query query;
  @Mock
  private Query rewrittenQuery;
  @Mock
  private CreateSourceAsProperties csasProperties;
  @Mock
  private ResultMaterialization resultMaterialization;

  private StatementRewriter<Object> rewriter;

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Before
  public void setup() {
    rewriter = new StatementRewriter<>(expressionRewriter, mockRewriter);
  }

  @Test
  public void shouldRewriteStatements() {
    // Given:
    final Statement statement1 = mock(Statement.class);
    final Statement statement2 = mock(Statement.class);
    final Statement rewritten1 = mock(Statement.class);
    final Statement rewritten2 = mock(Statement.class);
    final Statements statements =
        new Statements(location, ImmutableList.of(statement1, statement2));
    when(mockRewriter.apply(statement1, context)).thenReturn(rewritten1);
    when(mockRewriter.apply(statement2, context)).thenReturn(rewritten2);

    // When:
    final AstNode rewritten = rewriter.rewrite(statements, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(new Statements(location, ImmutableList.of(rewritten1, rewritten2)))
    );
  }

  private Query givenQuery(
      final Optional<WindowExpression> window,
      final Optional<Expression> where,
      final Optional<GroupBy> groupBy,
      final Optional<Expression> having
  ) {
    when(mockRewriter.apply(select, context)).thenReturn(rewrittenSelect);
    when(mockRewriter.apply(relation, context)).thenReturn(rewrittenRelation);
    return new Query(
        location,
        select,
        relation,
        window,
        where,
        groupBy,
        having,
        resultMaterialization,
        false,
        optionalInt
    );
  }

  @Test
  public void shouldRewriteQuery() {
    // Given:
    final Query query =
        givenQuery(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    // When:
    final AstNode rewritten = rewriter.rewrite(query, context);

    // Then:
    assertThat(rewritten, equalTo(new Query(
        location,
        rewrittenSelect,
        rewrittenRelation,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        resultMaterialization,
        false,
        optionalInt))
    );
  }

  @Test
  public void shouldRewriteQueryWithFilter() {
    // Given:
    final Query query =
        givenQuery(Optional.empty(), Optional.of(expression), Optional.empty(), Optional.empty());
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(query, context);

    // Then:
    assertThat(rewritten, equalTo(new Query(
        location,
        rewrittenSelect,
        rewrittenRelation,
        Optional.empty(),
        Optional.of(rewrittenExpression),
        Optional.empty(),
        Optional.empty(),
        resultMaterialization,
        false,
        optionalInt))
    );
  }

  @Test
  public void shouldRewriteQueryWithGroupBy() {
    // Given:
    final GroupBy groupBy = mock(GroupBy.class);
    final GroupBy rewrittenGroupBy = mock(GroupBy.class);
    final Query query =
        givenQuery(Optional.empty(), Optional.empty(), Optional.of(groupBy), Optional.empty());
    when(mockRewriter.apply(groupBy, context)).thenReturn(rewrittenGroupBy);

    // When:
    final AstNode rewritten = rewriter.rewrite(query, context);

    // Then:
    assertThat(rewritten, equalTo(new Query(
        location,
        rewrittenSelect,
        rewrittenRelation,
        Optional.empty(),
        Optional.empty(),
        Optional.of(rewrittenGroupBy),
        Optional.empty(),
        resultMaterialization,
        false,
        optionalInt))
    );
  }

  @Test
  public void shouldRewriteQueryWithWindow() {
    // Given:
    final WindowExpression window = mock(WindowExpression.class);
    final WindowExpression rewrittenWindow = mock(WindowExpression.class);
    final Query query =
        givenQuery(Optional.of(window), Optional.empty(), Optional.empty(), Optional.empty());
    when(mockRewriter.apply(window, context)).thenReturn(rewrittenWindow);

    // When:
    final AstNode rewritten = rewriter.rewrite(query, context);

    // Then:
    assertThat(rewritten, equalTo(new Query(
        location,
        rewrittenSelect,
        rewrittenRelation,
        Optional.of(rewrittenWindow),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        resultMaterialization,
        false,
        optionalInt))
    );
  }

  @Test
  public void shouldRewriteQueryWithHaving() {
    // Given:
    final Query query =
        givenQuery(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(expression));
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(query, context);

    // Then:
    assertThat(rewritten, equalTo(new Query(
        location,
        rewrittenSelect,
        rewrittenRelation,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(rewrittenExpression),
        resultMaterialization,
        false,
        optionalInt))
    );
  }

  @Test
  public void shouldRewriteSingleColumn() {
    // Given:
    final SingleColumn singleColumn = new SingleColumn(location, expression, ColumnName.of("foo"));
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(singleColumn, context);

    // Then:
    assertThat(rewritten, equalTo(new SingleColumn(location, rewrittenExpression, ColumnName.of("foo"))));
  }

  @Test
  public void shouldRewriteAliasedRelation() {
    // Given:
    final AliasedRelation aliasedRelation = new AliasedRelation(location, relation, SourceName.of("alias"));
    when(mockRewriter.apply(relation, context)).thenReturn(rewrittenRelation);

    // When:
    final AstNode rewritten = rewriter.rewrite(aliasedRelation, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(new AliasedRelation(location, rewrittenRelation, SourceName.of("alias"))));
  }

  private Join givenJoin(final Optional<WithinExpression> within) {
    when(mockRewriter.apply(relation, context)).thenReturn(rewrittenRelation);
    when(mockRewriter.apply(rightRelation, context)).thenReturn(rewrittenRightRelation);
    return new Join(location, Type.LEFT, relation, rightRelation, joinCriteria, within);
  }

  @Test
  public void shouldRewriteJoin() {
    // Given:
    final Join join = givenJoin(Optional.empty());

    // When:
    final AstNode rewritten = rewriter.rewrite(join, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new Join(
                location,
                Type.LEFT,
                rewrittenRelation,
                rewrittenRightRelation,
                joinCriteria,
                Optional.empty()))
    );
  }

  @Test
  public void shouldRewriteJoinWithWindowExpression() {
    // Given:
    final WithinExpression withinExpression = mock(WithinExpression.class);
    final WithinExpression rewrittenWithinExpression = mock(WithinExpression.class);
    final Join join = givenJoin(Optional.of(withinExpression));
    when(mockRewriter.apply(withinExpression, context)).thenReturn(rewrittenWithinExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(join, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new Join(
                location,
                Type.LEFT,
                rewrittenRelation,
                rewrittenRightRelation,
                joinCriteria,
                Optional.of(rewrittenWithinExpression)))
    );
  }

  @Test
  public void shouldRewriteWindowExpression() {
    // Given:
    final KsqlWindowExpression ksqlWindowExpression = mock(KsqlWindowExpression.class);
    final WindowExpression windowExpression =
        new WindowExpression(location, "name", ksqlWindowExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(windowExpression, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(new WindowExpression(location, "name", ksqlWindowExpression))
    );
  }

  private TableElement givenTableElement(final String name) {
    final TableElement element = mock(TableElement.class);
    when(element.getName()).thenReturn(ColumnName.of(name));
    when(element.getNamespace()).thenReturn(Namespace.VALUE);
    return element;
  }

  @Test
  public void shouldRewriteCreateStream() {
    // Given:
    final TableElement tableElement1 = givenTableElement("foo");
    final TableElement tableElement2 = givenTableElement("bar");
    final TableElement rewrittenTableElement1 = givenTableElement("baz");
    final TableElement rewrittenTableElement2 = givenTableElement("boz");
    final CreateStream cs = new CreateStream(
        location,
        sourceName,
        TableElements.of(tableElement1, tableElement2),
        false,
        sourceProperties
    );
    when(mockRewriter.apply(tableElement1, context)).thenReturn(rewrittenTableElement1);
    when(mockRewriter.apply(tableElement2, context)).thenReturn(rewrittenTableElement2);

    // When:
    final AstNode rewritten = rewriter.rewrite(cs, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new CreateStream(
                location,
                sourceName,
                TableElements.of(rewrittenTableElement1, rewrittenTableElement2),
                false,
                sourceProperties
            )
        )
    );
  }

  @Test
  public void shouldRewriteCSAS() {
    final CreateStreamAsSelect csas = new CreateStreamAsSelect(
        location,
        sourceName,
        query,
        false,
        csasProperties,
        Optional.empty()
    );
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);

    final AstNode rewritten = rewriter.rewrite(csas, context);

    assertThat(
        rewritten,
        equalTo(
            new CreateStreamAsSelect(
                location,
                sourceName,
                rewrittenQuery,
                false,
                csasProperties,
                Optional.empty()
            )
        )
    );
  }

  @Test
  public void shouldRewriteCSASWithPartitionBy() {
    final CreateStreamAsSelect csas = new CreateStreamAsSelect(
        location,
        sourceName,
        query,
        false,
        csasProperties,
        Optional.of(expression)
    );
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);

    final AstNode rewritten = rewriter.rewrite(csas, context);

    assertThat(
        rewritten,
        equalTo(
            new CreateStreamAsSelect(
                location,
                sourceName,
                rewrittenQuery,
                false,
                csasProperties,
                Optional.of(rewrittenExpression)
            )
        )
    );
  }

  @Test
  public void shouldRewriteCreateTable() {
    // Given:
    final TableElement tableElement1 = givenTableElement("foo");
    final TableElement tableElement2 = givenTableElement("bar");
    final TableElement rewrittenTableElement1 = givenTableElement("baz");
    final TableElement rewrittenTableElement2 = givenTableElement("boz");
    final CreateTable ct = new CreateTable(
        location,
        sourceName,
        TableElements.of(tableElement1, tableElement2),
        false,
        sourceProperties
    );
    when(mockRewriter.apply(tableElement1, context)).thenReturn(rewrittenTableElement1);
    when(mockRewriter.apply(tableElement2, context)).thenReturn(rewrittenTableElement2);

    // When:
    final AstNode rewritten = rewriter.rewrite(ct, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new CreateTable(
                location,
                sourceName,
                TableElements.of(rewrittenTableElement1, rewrittenTableElement2),
                false,
                sourceProperties
            )
        )
    );
  }

  @Test
  public void shouldRewriteCTAS() {
    // Given:
    final CreateTableAsSelect ctas = new CreateTableAsSelect(
        location,
        sourceName,
        query,
        false,
        csasProperties
    );
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);

    // When:
    final AstNode rewritten = rewriter.rewrite(ctas, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new CreateTableAsSelect(
                location,
                sourceName,
                rewrittenQuery,
                false,
                csasProperties
            )
        )
    );
  }

  @Test
  public void shouldRewriteInsertInto() {
    // Given:
    final InsertInto ii = new InsertInto(location, sourceName, query, Optional.empty());
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);

    // When:
    final AstNode rewritten = rewriter.rewrite(ii, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(new InsertInto(location, sourceName, rewrittenQuery, Optional.empty()))
    );
  }

  @Test
  public void shouldRewriteInsertIntoWithPartitionBy() {
    // Given:
    final InsertInto ii = new InsertInto(location, sourceName, query, Optional.of(expression));
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);

    // When:
    final AstNode rewritten = rewriter.rewrite(ii, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new InsertInto(
                location,
                sourceName,
                rewrittenQuery,
                Optional.of(rewrittenExpression)
            )
        )
    );
  }

  @Test
  public void shouldRewriteGroupBy() {
    // Given:
    final GroupingElement groupingElement1 = mock(GroupingElement.class);
    final GroupingElement groupingElement2 = mock(GroupingElement.class);
    final GroupingElement rewrittenGroupingElement1 = mock(GroupingElement.class);
    final GroupingElement rewrittenGroupingElement2 = mock(GroupingElement.class);
    final GroupBy groupBy = new GroupBy(
        location,
        ImmutableList.of(groupingElement1, groupingElement2)
    );
    when(mockRewriter.apply(groupingElement1, context)).thenReturn(rewrittenGroupingElement1);
    when(mockRewriter.apply(groupingElement2, context)).thenReturn(rewrittenGroupingElement2);

    // When:
    final AstNode rewritten = rewriter.rewrite(groupBy, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new GroupBy(
                location,
                ImmutableList.of(rewrittenGroupingElement1, rewrittenGroupingElement2)
            )
        )
    );
  }

  @Test
  public void shouldRewriteSimpleGroupBy() {
    // Given:
    final Expression expression2 = mock(Expression.class);
    final Expression rewrittenExpression2 = mock(Expression.class);
    final SimpleGroupBy groupBy =
        new SimpleGroupBy(location, ImmutableList.of(expression, expression2));
    when(expressionRewriter.apply(expression, context)).thenReturn(rewrittenExpression);
    when(expressionRewriter.apply(expression2, context)).thenReturn(rewrittenExpression2);

    // When:
    final AstNode rewritten = rewriter.rewrite(groupBy, context);

    // Then:
    assertThat(
        rewritten,
        equalTo(
            new SimpleGroupBy(
                location,
                ImmutableList.of(rewrittenExpression, rewrittenExpression2)
            )
        )
    );
  }

  @Test
  public void shouldRewriteExplainWithQuery() {
    // Given:
    final Explain explain = new Explain(location, Optional.empty(), Optional.of(query));
    when(mockRewriter.apply(query, context)).thenReturn(rewrittenQuery);

    // When:
    final AstNode rewritten = rewriter.rewrite(explain, context);

    // Then:
    assertThat(rewritten, is(new Explain(
        location,
        Optional.empty(),
        Optional.of(rewrittenQuery)
    )));
  }

  @Test
  public void shouldNotRewriteExplainWithId() {
    // Given:
    final Explain explain = new Explain(location, Optional.of("id"), Optional.empty());

    // When:
    final AstNode rewritten = rewriter.rewrite(explain, context);

    // Then:
    assertThat(rewritten, is(sameInstance(explain)));
  }
}
