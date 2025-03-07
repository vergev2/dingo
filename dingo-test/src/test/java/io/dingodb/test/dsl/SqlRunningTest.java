/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.test.dsl;

import io.dingodb.test.ConnectionFactory;
import io.dingodb.test.dsl.builder.SqlTestCaseYamlBuilder;
import io.dingodb.test.dsl.run.SqlTestRunner;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.sql.Connection;
import java.util.stream.Stream;

public class SqlRunningTest extends SqlTestRunner {
    @TestFactory
    public Stream<DynamicTest> testBasicDml() {
        return getTests(new BasicDmlCases());
    }

    @TestFactory
    public Stream<DynamicTest> testBasicQuery() {
        return getTests(new BasicQueryCases());
    }

    @TestFactory
    public Stream<DynamicTest> testCollectionDml() {
        return getTests(new CollectionDmlCases());
    }

    @TestFactory
    public Stream<DynamicTest> testCollectionQuery() {
        return getTests(new CollectionQueryCases());
    }

    @TestFactory
    public Stream<DynamicTest> testDateTimeQuery() {
        return getTests(new DateTimeQueryCases());
    }

    @TestFactory
    public Stream<DynamicTest> testI40VsF8kQuery() {
        return getTests(SqlTestCaseYamlBuilder.of("i40_vs_f8k/query_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testI4kF80Query() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_f80/query_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testI4kL0Dml() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_l0/dml_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testI4kVsL0Query() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs_l0/query_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testParameterDml() {
        return getTests(new ParameterDmlCases());
    }

    @TestFactory
    public Stream<DynamicTest> testParameterQuery() {
        return getTests(new ParameterQueryCases());
    }

    @TestFactory
    public Stream<DynamicTest> testJoin() {
        return getTests(SqlTestCaseYamlBuilder.of("join_i4k_vs_i4__i4k_vs_2/join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testJoin1() {
        return getTests(SqlTestCaseYamlBuilder.of("join_i4k_vs0_i40__i4k_vs/join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testJoin2() {
        return getTests(SqlTestCaseYamlBuilder.of("join_i4k_vs0_5_f80_2_i40_2_ts0__i4k_vs0_i40_2/join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testJoin3() {
        return getTests(SqlTestCaseYamlBuilder.of("join_i4k_vs_vs0_ts0_vs_i40__i4k_vs_i4/join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testJoin4() {
        return getTests(SqlTestCaseYamlBuilder.of("join_vsk_vs0_i40_vs0_2__vsk_vs0_2__vsk_2_i40/join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testSelfJoin() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs0_i40/self_join_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testTransfer() {
        return getTests(new TransferCases());
    }

    @TestFactory
    public Stream<DynamicTest> testLike() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs0/query_cases_like.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testBetweenAnd() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs0/query_cases_between.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testDeleteRange() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs0/delete_range_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testFunctionScan() {
        return getTests(SqlTestCaseYamlBuilder.of("i4k_vs0/function_scan_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testLike1() {
        return getTests(SqlTestCaseYamlBuilder.of("i40_vsk/query_cases.yml"));
    }

    @TestFactory
    public Stream<DynamicTest> testDefaultValue() {
        return getTests(new DefaultValueCases());
    }

    @TestFactory
    public Stream<DynamicTest> testCancel() {
        return getTests(new CancelCases());
    }

    @TestFactory
    public Stream<DynamicTest> testException() {
        return getTests(new ExceptionCases());
    }

    @Disabled
    @TestFactory
    public Stream<DynamicTest> testStressDml() {
        return getTests(new StressDmlCases());
    }

    @Override
    protected void setup() throws Exception {
        ConnectionFactory.initLocalEnvironment();
    }

    @Override
    protected void cleanUp() {
        ConnectionFactory.cleanUp();
    }

    @Override
    protected Connection getConnection() throws Exception {
        return ConnectionFactory.getConnection();
    }
}
