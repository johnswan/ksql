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

package io.confluent.ksql.execution.util;

import io.confluent.ksql.execution.expression.tree.ArithmeticBinaryExpression;
import io.confluent.ksql.execution.expression.tree.ArithmeticUnaryExpression;
import io.confluent.ksql.execution.expression.tree.BetweenPredicate;
import io.confluent.ksql.execution.expression.tree.BooleanLiteral;
import io.confluent.ksql.execution.expression.tree.Cast;
import io.confluent.ksql.execution.expression.tree.ColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.ComparisonExpression;
import io.confluent.ksql.execution.expression.tree.DecimalLiteral;
import io.confluent.ksql.execution.expression.tree.DereferenceExpression;
import io.confluent.ksql.execution.expression.tree.DoubleLiteral;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.ExpressionVisitor;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.expression.tree.InListExpression;
import io.confluent.ksql.execution.expression.tree.InPredicate;
import io.confluent.ksql.execution.expression.tree.IntegerLiteral;
import io.confluent.ksql.execution.expression.tree.IsNotNullPredicate;
import io.confluent.ksql.execution.expression.tree.IsNullPredicate;
import io.confluent.ksql.execution.expression.tree.LikePredicate;
import io.confluent.ksql.execution.expression.tree.LogicalBinaryExpression;
import io.confluent.ksql.execution.expression.tree.LongLiteral;
import io.confluent.ksql.execution.expression.tree.NotExpression;
import io.confluent.ksql.execution.expression.tree.NullLiteral;
import io.confluent.ksql.execution.expression.tree.SearchedCaseExpression;
import io.confluent.ksql.execution.expression.tree.SimpleCaseExpression;
import io.confluent.ksql.execution.expression.tree.StringLiteral;
import io.confluent.ksql.execution.expression.tree.SubscriptExpression;
import io.confluent.ksql.execution.expression.tree.TimeLiteral;
import io.confluent.ksql.execution.expression.tree.TimestampLiteral;
import io.confluent.ksql.execution.expression.tree.Type;
import io.confluent.ksql.execution.expression.tree.WhenClause;
import io.confluent.ksql.execution.function.udf.structfieldextractor.FetchFieldFromStruct;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.function.KsqlAggregateFunction;
import io.confluent.ksql.function.KsqlFunctionException;
import io.confluent.ksql.function.UdfFactory;
import io.confluent.ksql.schema.Operator;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.SchemaConverters;
import io.confluent.ksql.schema.ksql.SchemaConverters.ConnectToSqlTypeConverter;
import io.confluent.ksql.schema.ksql.SchemaConverters.SqlToConnectTypeConverter;
import io.confluent.ksql.schema.ksql.types.SqlArray;
import io.confluent.ksql.schema.ksql.types.SqlMap;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.VisitorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.connect.data.Schema;

@SuppressWarnings("deprecation") // Need to migrate away from Connect Schema use.
public class ExpressionTypeManager {

  private static final SqlToConnectTypeConverter SQL_TO_CONNECT_SCHEMA_CONVERTER =
      SchemaConverters.sqlToConnectConverter();

  private static final ConnectToSqlTypeConverter CONNECT_TO_SQL_SCHEMA_CONVERTER =
      SchemaConverters.connectToSqlConverter();

  private final LogicalSchema schema;
  private final FunctionRegistry functionRegistry;

  public ExpressionTypeManager(
      final LogicalSchema schema,
      final FunctionRegistry functionRegistry
  ) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.functionRegistry = Objects.requireNonNull(functionRegistry, "functionRegistry");
  }

  /**
   * @deprecated use getExpressionSqlType in new code.
   */
  @SuppressWarnings("DeprecatedIsStillUsed")
  @Deprecated
  public Schema getExpressionSchema(final Expression expression) {
    final ExpressionTypeContext expressionTypeContext = new ExpressionTypeContext();
    new Visitor().process(expression, expressionTypeContext);
    return expressionTypeContext.getSchema();
  }

  public SqlType getExpressionSqlType(final Expression expression) {
    final ExpressionTypeContext expressionTypeContext = new ExpressionTypeContext();
    new Visitor().process(expression, expressionTypeContext);
    return expressionTypeContext.getSqlType();
  }

  static class ExpressionTypeContext {

    private Schema schema;
    private SqlType sqlType;

    public Schema getSchema() {
      return schema;
    }

    SqlType getSqlType() {
      return sqlType;
    }

    void setSchema(
        final SqlType sqlType,
        final Schema schema
    ) {
      this.sqlType = sqlType;
      this.schema = schema;
    }
  }

  private class Visitor implements ExpressionVisitor<Void, ExpressionTypeContext> {

    @Override
    public Void visitArithmeticBinary(
        final ArithmeticBinaryExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      process(node.getLeft(), expressionTypeContext);
      final Schema leftType = expressionTypeContext.getSchema();
      process(node.getRight(), expressionTypeContext);
      final Schema rightType = expressionTypeContext.getSchema();

      final Schema schema = resolveArithmeticType(leftType, rightType, node.getOperator());
      final SqlType sqlType = CONNECT_TO_SQL_SCHEMA_CONVERTER.toSqlType(schema);

      expressionTypeContext.setSchema(sqlType, schema);
      return null;
    }

    @Override
    public Void visitArithmeticUnary(
        final ArithmeticUnaryExpression node,
        final ExpressionTypeContext context
    ) {
      process(node.getValue(), context);
      return null;
    }

    @Override
    public Void visitNotExpression(
        final NotExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitCast(
        final Cast node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      final SqlType sqlType = node.getType().getSqlType();
      if (!sqlType.supportsCast()) {
        throw new KsqlFunctionException("Only casts to primitive types or decimals "
            + "are supported: " + sqlType);
      }

      final Schema castType = SQL_TO_CONNECT_SCHEMA_CONVERTER.toConnectSchema(sqlType);

      expressionTypeContext.setSchema(sqlType, castType);
      return null;
    }

    @Override
    public Void visitComparisonExpression(
        final ComparisonExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      process(node.getLeft(), expressionTypeContext);
      final Schema leftSchema = expressionTypeContext.getSchema();
      process(node.getRight(), expressionTypeContext);
      final Schema rightSchema = expressionTypeContext.getSchema();
      ComparisonUtil.isValidComparison(leftSchema, node.getType(), rightSchema);
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitBetweenPredicate(
        final BetweenPredicate node,
        final ExpressionTypeContext context
    ) {
      context.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitQualifiedNameReference(
        final ColumnReferenceExp node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      final Column schemaColumn = schema.findValueColumn(node.getReference().toString())
          .orElseThrow(() ->
              new KsqlException(String.format("Invalid Expression %s.", node.toString())));

      final Schema schema = SQL_TO_CONNECT_SCHEMA_CONVERTER.toConnectSchema(schemaColumn.type());
      expressionTypeContext.setSchema(schemaColumn.type(), schema);
      return null;
    }

    @Override
    public Void visitDereferenceExpression(
        final DereferenceExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw new IllegalArgumentException("Dereferenced expressions should have been rewritten to "
          + FetchFieldFromStruct.FUNCTION_NAME + " by this point");
    }

    @Override
    public Void visitStringLiteral(
        final StringLiteral node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.STRING, Schema.OPTIONAL_STRING_SCHEMA);
      return null;
    }

    @Override
    public Void visitBooleanLiteral(
        final BooleanLiteral node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitLongLiteral(
        final LongLiteral node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BIGINT, Schema.OPTIONAL_INT64_SCHEMA);
      return null;
    }

    @Override
    public Void visitIntegerLiteral(
        final IntegerLiteral node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.INTEGER, Schema.OPTIONAL_INT32_SCHEMA);
      return null;
    }

    @Override
    public Void visitDoubleLiteral(
        final DoubleLiteral node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.DOUBLE, Schema.OPTIONAL_FLOAT64_SCHEMA);
      return null;
    }

    @Override
    public Void visitNullLiteral(
        final NullLiteral node,
        final ExpressionTypeContext context
    ) {
      context.setSchema(null, null);
      return null;
    }

    @Override
    public Void visitLikePredicate(
        final LikePredicate node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitIsNotNullPredicate(
        final IsNotNullPredicate node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitIsNullPredicate(
        final IsNullPredicate node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      expressionTypeContext.setSchema(SqlTypes.BOOLEAN, Schema.OPTIONAL_BOOLEAN_SCHEMA);
      return null;
    }

    @Override
    public Void visitSearchedCaseExpression(
        final SearchedCaseExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      validateSearchedCaseExpression(node);
      process(node.getWhenClauses().get(0).getResult(), expressionTypeContext);
      return null;
    }

    @Override
    public Void visitSubscriptExpression(
        final SubscriptExpression node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      process(node.getBase(), expressionTypeContext);
      final Schema arrayMapSchema = expressionTypeContext.getSchema();
      final SqlType arrayMapType = expressionTypeContext.getSqlType();

      final SqlType valueType;
      if (arrayMapType instanceof SqlMap) {
        valueType = ((SqlMap)arrayMapType).getValueType();
      } else if (arrayMapType instanceof SqlArray) {
        valueType = ((SqlArray)arrayMapType).getItemType();
      } else {
        throw new UnsupportedOperationException("Unsupported container type: " + arrayMapType);
      }

      expressionTypeContext.setSchema(valueType, arrayMapSchema.valueSchema());
      return null;
    }

    @Override
    public Void visitFunctionCall(
        final FunctionCall node,
        final ExpressionTypeContext expressionTypeContext
    ) {
      if (functionRegistry.isAggregate(node.getName().name())) {
        final Schema schema = node.getArguments().isEmpty()
            ? FunctionRegistry.DEFAULT_FUNCTION_ARG_SCHEMA
            : getExpressionSchema(node.getArguments().get(0));

        final KsqlAggregateFunction aggFunc = functionRegistry
            .getAggregate(node.getName().name(), schema);

        final Schema returnSchema = aggFunc.getReturnType();

        final SqlType returnType = CONNECT_TO_SQL_SCHEMA_CONVERTER.toSqlType(returnSchema);

        expressionTypeContext.setSchema(returnType, returnSchema);
        return null;
      }

      if (node.getName().name().equalsIgnoreCase(FetchFieldFromStruct.FUNCTION_NAME)) {
        process(node.getArguments().get(0), expressionTypeContext);
        final Schema firstArgSchema = expressionTypeContext.getSchema();
        final String fieldName = ((StringLiteral) node.getArguments().get(1)).getValue();
        if (firstArgSchema.field(fieldName) == null) {
          throw new KsqlException(String.format("Could not find field %s in %s.",
              fieldName,
              node.getArguments().get(0).toString()));
        }
        final Schema returnSchema = firstArgSchema.field(fieldName).schema();
        final SqlType returnType = CONNECT_TO_SQL_SCHEMA_CONVERTER.toSqlType(returnSchema);
        expressionTypeContext.setSchema(returnType, returnSchema);
      } else {
        final UdfFactory udfFactory = functionRegistry.getUdfFactory(node.getName().name());
        final List<Schema> argTypes = new ArrayList<>();
        for (final Expression expression : node.getArguments()) {
          process(expression, expressionTypeContext);
          argTypes.add(expressionTypeContext.getSchema());
        }
        final Schema returnSchema = udfFactory.getFunction(argTypes).getReturnType(argTypes);
        final SqlType returnType = CONNECT_TO_SQL_SCHEMA_CONVERTER.toSqlType(returnSchema);
        expressionTypeContext.setSchema(returnType, returnSchema);
      }
      return null;
    }

    @Override
    public Void visitLogicalBinaryExpression(
        final LogicalBinaryExpression node,
        final ExpressionTypeContext context) {
      process(node.getLeft(), context);
      process(node.getRight(), context);
      return null;
    }

    @Override
    public Void visitType(
        final Type type,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.illegalState(this, type);
    }

    @Override
    public Void visitTimeLiteral(
        final TimeLiteral timeLiteral,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, timeLiteral);
    }

    @Override
    public Void visitTimestampLiteral(
        final TimestampLiteral timestampLiteral,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, timestampLiteral);
    }

    @Override
    public Void visitDecimalLiteral(
        final DecimalLiteral decimalLiteral,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, decimalLiteral);
    }

    @Override
    public Void visitSimpleCaseExpression(
        final SimpleCaseExpression simpleCaseExpression,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, simpleCaseExpression);
    }

    @Override
    public Void visitInListExpression(
        final InListExpression inListExpression,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, inListExpression);
    }

    @Override
    public Void visitInPredicate(
        final InPredicate inPredicate,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.unsupportedOperation(this, inPredicate);
    }

    @Override
    public Void visitWhenClause(
        final WhenClause whenClause,
        final ExpressionTypeContext expressionTypeContext
    ) {
      throw VisitorUtil.illegalState(this, whenClause);
    }

    private Schema resolveArithmeticType(
        final Schema leftSchema,
        final Schema rightSchema,
        final Operator operator) {
      return SchemaUtil.resolveBinaryOperatorResultType(leftSchema, rightSchema, operator);
    }

    private void validateSearchedCaseExpression(
        final SearchedCaseExpression searchedCaseExpression) {
      final Schema firstResultSchema = getExpressionSchema(
          searchedCaseExpression.getWhenClauses().get(0).getResult());
      searchedCaseExpression.getWhenClauses()
          .forEach(whenClause -> validateWhenClause(whenClause, firstResultSchema));
      searchedCaseExpression.getDefaultValue()
          .map(ExpressionTypeManager.this::getExpressionSchema)
          .filter(defaultSchema -> !firstResultSchema.equals(defaultSchema))
          .ifPresent(badSchema -> {
            throw new KsqlException("Invalid Case expression."
                + " Schema for the default clause should be the same as schema for THEN clauses."
                + " Result scheme: " + firstResultSchema + "."
                + " Schema for default expression is " + badSchema);
          });
    }

    private void validateWhenClause(final WhenClause whenClause,
        final Schema expectedResultSchema) {
      final Schema operandSchema = getExpressionSchema(whenClause.getOperand());
      if (!operandSchema.equals(Schema.OPTIONAL_BOOLEAN_SCHEMA)) {
        throw new KsqlException("When operand schema should be boolean. Schema for ("
            + whenClause.getOperand() + ") is " + operandSchema);
      }
      final Schema resultSchema = getExpressionSchema(whenClause.getResult());
      if (!expectedResultSchema.equals(resultSchema)) {
        throw new KsqlException("Invalid Case expression."
            + " Schemas for 'THEN' clauses should be the same."
            + " Result schema: " + expectedResultSchema + "."
            + " Schema for THEN expression '" + whenClause + "'"
            + " is " + resultSchema);
      }
    }
  }
}
