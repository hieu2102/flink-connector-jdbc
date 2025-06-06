/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.jdbc.core.table.source;

import org.apache.flink.connector.jdbc.JdbcTestBase;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.api.bridge.java.internal.StreamTableEnvironmentImpl;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.resolver.ExpressionResolver;
import org.apache.flink.table.planner.calcite.FlinkContext;
import org.apache.flink.table.planner.calcite.FlinkTypeFactory;
import org.apache.flink.table.planner.calcite.FlinkTypeSystem;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.expressions.RexNodeExpression;
import org.apache.flink.table.planner.plan.utils.RexNodeToExpressionConverter;
import org.apache.flink.table.planner.runtime.utils.StreamTestSink;
import org.apache.flink.table.types.logical.RowType;

import org.apache.calcite.rex.RexBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

/** Test for {@link JdbcFilterPushdownPreparedStatementVisitor}. */
class JdbcFilterPushdownPreparedStatementVisitorTest {

    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    public static final String DRIVER_CLASS = "org.apache.derby.jdbc.EmbeddedDriver";
    public static final String DB_URL = "jdbc:derby:memory:test";
    public static final String INPUT_TABLE = "jdbDynamicTableSource";

    public static StreamExecutionEnvironment env;
    public static TableEnvironment tEnv;

    @BeforeEach
    void before() throws ClassNotFoundException, SQLException {
        env = StreamExecutionEnvironment.getExecutionEnvironment();
        tEnv = StreamTableEnvironment.create(env);

        System.setProperty(
                "derby.stream.error.field", JdbcTestBase.class.getCanonicalName() + ".DEV_NULL");
        Class.forName(DRIVER_CLASS);

        try (Connection conn = DriverManager.getConnection(DB_URL + ";create=true");
                Statement statement = conn.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE "
                            + INPUT_TABLE
                            + " ("
                            + "id BIGINT NOT NULL,"
                            + "description VARCHAR(200) NOT NULL,"
                            + "timestamp6_col TIMESTAMP, "
                            + "timestamp9_col TIMESTAMP, "
                            + "time_col TIME, "
                            + "real_col FLOAT(23), "
                            + // A precision of 23 or less makes FLOAT equivalent to REAL.
                            "double_col FLOAT(24),"
                            + // A precision of 24 or greater makes FLOAT equivalent to DOUBLE
                            // PRECISION.
                            "decimal_col DECIMAL(10, 4))");
        }
        // Create table in Flink, this can be reused across test cases
        tEnv.executeSql(
                "CREATE TABLE "
                        + INPUT_TABLE
                        + "("
                        + "id BIGINT,"
                        + "description VARCHAR(200),"
                        + "timestamp6_col TIMESTAMP(6),"
                        + "timestamp9_col TIMESTAMP(9),"
                        + "time_col TIME,"
                        + "real_col FLOAT,"
                        + "double_col DOUBLE,"
                        + "decimal_col DECIMAL(10, 4)"
                        + ") WITH ("
                        + "  'connector'='jdbc',"
                        + "  'url'='"
                        + DB_URL
                        + "',"
                        + "  'table-name'='"
                        + INPUT_TABLE
                        + "'"
                        + ")");
    }

    @AfterEach
    void clearOutputTable() throws Exception {
        Class.forName(DRIVER_CLASS);
        try (Connection conn = DriverManager.getConnection(DB_URL);
                Statement stat = conn.createStatement()) {
            stat.executeUpdate("DROP TABLE " + INPUT_TABLE);
        }
        StreamTestSink.clear();
    }

    @Test
    void testSimpleExpressionPrimitiveType() {
        ResolvedSchema schema = tEnv.sqlQuery("SELECT * FROM " + INPUT_TABLE).getResolvedSchema();
        Arrays.asList(
                        new Object[] {"id = 6", "id = ?", 6L},
                        new Object[] {"id >= 6", "id >= ?", 6},
                        new Object[] {"id > 6", "id > ?", 6},
                        new Object[] {"id < 6", "id < ?", 6},
                        new Object[] {"id <= 5", "id <= ?", 5},
                        new Object[] {"description = 'Halo'", "description = ?", "Halo"},
                        new Object[] {"real_col > 0.5", "real_col > ?", new BigDecimal("0.5")},
                        new Object[] {
                            "double_col <= -0.3", "double_col <= ?", new BigDecimal("-0.3")
                        },
                        new Object[] {"description LIKE '_bcd%'", "description LIKE ?", "_bcd%"})
                .forEach(
                        inputs ->
                                assertSimpleInputExprEqualsOutExpr(
                                        (String) inputs[0],
                                        schema,
                                        (String) inputs[1],
                                        (Serializable) inputs[2]));
    }

    @Test
    void testComplexExpressionDatetime() {
        ResolvedSchema schema = tEnv.sqlQuery("SELECT * FROM " + INPUT_TABLE).getResolvedSchema();
        String andExpr = "id = 6 AND timestamp6_col = TIMESTAMP '2022-01-01 07:00:01.333'";
        Serializable[] expectedParams1 = {6L, Timestamp.valueOf("2022-01-01 07:00:01.333000")};
        assertGeneratedSQLString(
                andExpr, schema, "((id = ?) AND (timestamp6_col = ?))", expectedParams1);

        Serializable[] expectedParams2 = {Timestamp.valueOf("2022-01-01 07:00:01.333"), "Halo"};
        String orExpr =
                "timestamp9_col = TIMESTAMP '2022-01-01 07:00:01.333' OR description = 'Halo'";
        assertGeneratedSQLString(
                orExpr, schema, "((timestamp9_col = ?) OR (description = ?))", expectedParams2);
    }

    @Test
    void testExpressionWithNull() {
        ResolvedSchema schema = tEnv.sqlQuery("SELECT * FROM " + INPUT_TABLE).getResolvedSchema();
        String andExpr = "id = NULL AND real_col <= 0.6";

        Serializable[] expectedParams1 = {null, new BigDecimal("0.6")};
        assertGeneratedSQLString(
                andExpr, schema, "((id = ?) AND (real_col <= ?))", expectedParams1);

        Serializable[] expectedParams2 = {6L, null};
        String orExpr = "id = 6 OR description = NULL";
        assertGeneratedSQLString(
                orExpr, schema, "((id = ?) OR (description = ?))", expectedParams2);
    }

    @Test
    void testExpressionIsNull() {
        ResolvedSchema schema = tEnv.sqlQuery("SELECT * FROM " + INPUT_TABLE).getResolvedSchema();
        String andExpr = "id IS NULL AND real_col <= 0.6";

        Serializable[] expectedParams1 = {new BigDecimal("0.6")};
        assertGeneratedSQLString(
                andExpr, schema, "((id IS NULL) AND (real_col <= ?))", expectedParams1);

        Serializable[] expectedParams2 = {6L};
        String orExpr = "id = 6 OR description IS NOT NULL";
        assertGeneratedSQLString(
                orExpr, schema, "((id = ?) OR (description IS NOT NULL))", expectedParams2);
    }

    @Test
    void testComplexExpressionPrimitiveType() {
        ResolvedSchema schema = tEnv.sqlQuery("SELECT * FROM " + INPUT_TABLE).getResolvedSchema();
        String andExpr = "id = NULL AND real_col <= 0.6";
        Serializable[] expectedParams1 = {null, new BigDecimal("0.6")};
        assertGeneratedSQLString(
                andExpr, schema, "((id = ?) AND (real_col <= ?))", expectedParams1);

        String orExpr = "id = 6 OR description = NULL";
        Serializable[] expectedParams2 = {6L, null};
        assertGeneratedSQLString(
                orExpr, schema, "((id = ?) OR (description = ?))", expectedParams2);
    }

    private void assertGeneratedSQLString(
            String inputExpr,
            ResolvedSchema schema,
            String expectedOutputExpr,
            Serializable[] expectedParams) {}

    private void assertSimpleInputExprEqualsOutExpr(
            String inputExpr, ResolvedSchema schema, String expectedOutput, Serializable param) {
        // our visitor always wrap expression
        Serializable[] params = new Serializable[1];
        params[0] = param;
        assertGeneratedSQLString(inputExpr, schema, "(" + expectedOutput + ")", params);
    }

    /**
     * Resolve a SQL filter expression against a Schema, this method makes use of some
     * implementation details of Flink.
     */
    private List<ResolvedExpression> resolveSQLFilterToExpression(
            String sqlExp, ResolvedSchema schema) {
        StreamTableEnvironmentImpl tbImpl = (StreamTableEnvironmentImpl) tEnv;

        FlinkContext ctx = ((PlannerBase) tbImpl.getPlanner()).getFlinkContext();
        CatalogManager catMan = tbImpl.getCatalogManager();
        FunctionCatalog funCat = ctx.getFunctionCatalog();
        RowType sourceType = (RowType) schema.toSourceRowDataType().getLogicalType();

        FlinkTypeFactory typeFactory = new FlinkTypeFactory(classLoader, FlinkTypeSystem.INSTANCE);
        RexNodeToExpressionConverter converter =
                getConverter(
                        new RexBuilder(typeFactory),
                        sourceType.getFieldNames().toArray(new String[0]),
                        funCat,
                        catMan);

        RexNodeExpression rexExp =
                (RexNodeExpression) tbImpl.getParser().parseSqlExpression(sqlExp, sourceType, null);
        ResolvedExpression resolvedExp =
                rexExp.getRexNode()
                        .accept(converter)
                        .getOrElse(
                                () -> {
                                    throw new IllegalArgumentException(
                                            "Cannot convert "
                                                    + rexExp.getRexNode()
                                                    + " to Expression, this likely "
                                                    + "means you used some function(s) not "
                                                    + "supported with this setup.");
                                });
        ExpressionResolver resolver =
                ExpressionResolver.resolverFor(
                                tEnv.getConfig(),
                                classLoader,
                                name -> Optional.empty(),
                                funCat.asLookup(
                                        str -> {
                                            throw new TableException(
                                                    "We should not need to lookup any expressions at this point");
                                        }),
                                catMan.getDataTypeFactory(),
                                (sqlExpression, inputRowType, outputType) -> {
                                    throw new TableException(
                                            "SQL expression parsing is not supported at this location.");
                                })
                        .build();

        return resolver.resolve(Arrays.asList(resolvedExp));
    }

    private RexNodeToExpressionConverter getConverter(
            RexBuilder rexBuilder,
            String[] inputNames,
            FunctionCatalog functionCatalog,
            CatalogManager catalogManager) {
        try {
            Class<?> clazz =
                    Class.forName(
                            RexNodeToExpressionConverter.class.getCanonicalName(),
                            false,
                            classLoader);

            Constructor<?>[] constructors = clazz.getConstructors();

            for (Constructor<?> constructor : constructors) {
                if (constructor.getParameterCount() == 4) {
                    return (RexNodeToExpressionConverter)
                            constructor.newInstance(
                                    rexBuilder, inputNames, functionCatalog, catalogManager);
                }
                if (constructor.getParameterCount() == 5
                        && TimeZone.class.equals(constructor.getParameters()[4].getType())) {
                    return (RexNodeToExpressionConverter)
                            constructor.newInstance(
                                    rexBuilder,
                                    inputNames,
                                    functionCatalog,
                                    catalogManager,
                                    TimeZone.getTimeZone(tEnv.getConfig().getLocalTimeZone()));
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to instantiate RexNodeToExpressionConverter", e);
        }
        throw new RuntimeException("No suitable RexNodeToExpressionConverter constructor found.");
    }
}
