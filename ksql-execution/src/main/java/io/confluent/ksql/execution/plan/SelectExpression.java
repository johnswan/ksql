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

package io.confluent.ksql.execution.plan;

import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.name.ColumnName;
import java.util.Objects;
import javax.annotation.concurrent.Immutable;

/**
 * Pojo holding field name and expression of a select item.
 */
@Immutable
public final class SelectExpression {

  private final ColumnName name;
  private final Expression expression;

  private SelectExpression(final ColumnName name, final Expression expression) {
    this.name = Objects.requireNonNull(name, "name");
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public static SelectExpression of(final ColumnName name, final Expression expression) {
    return new SelectExpression(name, expression);
  }

  public ColumnName getName() {
    return name;
  }

  public Expression getExpression() {
    return expression;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final SelectExpression that = (SelectExpression) o;
    return Objects.equals(name, that.name)
        && Objects.equals(expression, that.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, expression);
  }

  @Override
  public String toString() {
    return "SelectExpression{"
        + "name='" + name + '\''
        + ", expression=" + expression
        + '}';
  }
}
