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

package io.dingodb.driver;

import com.google.common.collect.ImmutableList;
import io.dingodb.calcite.DingoParser;
import io.dingodb.calcite.DingoRelOptTable;
import io.dingodb.calcite.DingoTable;
import io.dingodb.calcite.grammar.ddl.DingoSqlCreateTable;
import io.dingodb.calcite.operation.DdlOperation;
import io.dingodb.calcite.operation.DmlOperation;
import io.dingodb.calcite.operation.Operation;
import io.dingodb.calcite.operation.QueryOperation;
import io.dingodb.calcite.rel.AutoIncrementShuttle;
import io.dingodb.calcite.rel.DingoValues;
import io.dingodb.calcite.rel.DingoVector;
import io.dingodb.calcite.type.converter.DefinitionMapper;
import io.dingodb.calcite.visitor.DingoJobVisitor;
import io.dingodb.common.CommonId;
import io.dingodb.common.Location;
import io.dingodb.common.type.DingoType;
import io.dingodb.common.util.Optional;
import io.dingodb.common.util.Utils;
import io.dingodb.exec.base.Job;
import io.dingodb.exec.base.JobManager;
import io.dingodb.exec.transaction.base.ITransaction;
import io.dingodb.meta.MetaService;
import io.dingodb.transaction.api.LockType;
import io.dingodb.transaction.api.TableLock;
import io.dingodb.transaction.api.TableLockService;
import io.dingodb.tso.TsoService;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.AvaticaParameter;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.SqlType;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.server.DdlExecutor;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import static io.dingodb.exec.transaction.base.TransactionType.NONE;
import static io.dingodb.exec.transaction.base.TransactionType.OPTIMISTIC;

@Slf4j
public final class DingoDriverParser extends DingoParser {
    private final DingoConnection connection;

    public DingoDriverParser(@NonNull DingoConnection connection) {
        super(connection.getContext());
        this.connection = connection;
    }

    private static RelDataType makeStruct(RelDataTypeFactory typeFactory, @NonNull RelDataType type) {
        if (type.isStruct()) {
            return type;
        }
        return typeFactory.builder().add("$0", type).build();
    }

    @NonNull
    private static List<ColumnMetaData> getColumnMetaDataList(
        JavaTypeFactory typeFactory,
        @NonNull RelDataType jdbcType,
        List<? extends @Nullable List<String>> originList
    ) {
        List<RelDataTypeField> fieldList = jdbcType.getFieldList();
        final List<ColumnMetaData> columns = new ArrayList<>(fieldList.size());
        for (int i = 0; i < fieldList.size(); ++i) {
            RelDataTypeField field = fieldList.get(i);
            if (field.getName().equalsIgnoreCase("_rowid")) {
                continue;
            }
            columns.add(metaData(
                typeFactory,
                columns.size(),
                field.getName(),
                field.getType(),
                originList.get(i)
            ));
        }
        return columns;
    }

    public static ColumnMetaData.AvaticaType avaticaType(
        @NonNull JavaTypeFactory typeFactory,
        @NonNull RelDataType type
    ) {
        SqlTypeName typeName = type.getSqlTypeName();
        switch (typeName) {
            case ARRAY:
            case MULTISET:
                return ColumnMetaData.array(
                    avaticaType(typeFactory, Objects.requireNonNull(type.getComponentType())),
                    type.getSqlTypeName().getName(),
                    ColumnMetaData.Rep.of(typeFactory.getJavaClass(type))
                );
            default:
                return ColumnMetaData.scalar(
                    type.getSqlTypeName().getJdbcOrdinal(),
                    type.getSqlTypeName().getName(),
                    ColumnMetaData.Rep.of(typeFactory.getJavaClass(type))
                );
        }
    }

    @NonNull
    private static ColumnMetaData metaData(
        @NonNull JavaTypeFactory typeFactory,
        int ordinal,
        String fieldName,
        @NonNull RelDataType type,
        @Nullable List<String> origins
    ) {
        ColumnMetaData.AvaticaType avaticaType = avaticaType(typeFactory, type);
        return new ColumnMetaData(
            ordinal,
            false,
            true,
            false,
            false,
            type.isNullable() ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls,
            true,
            type.getPrecision(),
            fieldName,
            origin(origins, 0),
            origin(origins, 2),
            type.getPrecision(),
            type.getScale(),
            origin(origins, 1),
            null,
            avaticaType,
            true,
            false,
            false,
            avaticaType.id == SqlType.FLOAT.id ? "java.lang.Float" : avaticaType.columnClassName()
        );
    }

    private static @Nullable String origin(@Nullable List<String> origins, int offsetFromEnd) {
        return origins == null || offsetFromEnd >= origins.size()
            ? null : origins.get(origins.size() - 1 - offsetFromEnd);
    }

    @NonNull
    private static List<AvaticaParameter> createParameterList(@NonNull RelDataType parasType) {
        List<RelDataTypeField> fieldList = parasType.getFieldList();
        final List<AvaticaParameter> parameters = new ArrayList<>(fieldList.size());
        for (RelDataTypeField field : fieldList) {
            RelDataType type = field.getType();
            parameters.add(
                new AvaticaParameter(
                    false,
                    type.getPrecision(),
                    type.getScale(),
                    type.getSqlTypeName().getJdbcOrdinal(),
                    type.getSqlTypeName().toString(),
                    Object.class.getName(),
                    field.getName()));
        }
        return parameters;
    }

    @Nonnull
    public Meta.Signature parseQuery(
        JobManager jobManager,
        String jobIdPrefix,
        String sql
    ) {
        SqlNode sqlNode;
        try {
            sqlNode = parse(sql);
            if (sqlNode instanceof DingoSqlCreateTable) {
                ((DingoSqlCreateTable) sqlNode).setOriginalCreateSql(sql);
            }
        } catch (SqlParseException e) {
            throw ExceptionUtils.toRuntime(e);
        }

        JavaTypeFactory typeFactory = connection.getTypeFactory();
        final Meta.CursorFactory cursorFactory = Meta.CursorFactory.ARRAY;
        // for compatible mysql protocol
        if (compatibleMysql(sqlNode)) {
            DingoDdlVerify.verify(sqlNode, connection);
            Operation operation = convertToOperation(sqlNode, connection, connection.getContext());
            Meta.StatementType statementType;
            List<ColumnMetaData> columns = new ArrayList<>();
            if (sqlNode.getKind() == SqlKind.SELECT) {
                columns = ((QueryOperation)operation).columns().stream().map(column -> metaData(typeFactory, 0, column,
                    new BasicSqlType(RelDataTypeSystem.DEFAULT, SqlTypeName.CHAR), null))
                    .collect(Collectors.toList());
                statementType = Meta.StatementType.SELECT;
            } else if (sqlNode.getKind() == SqlKind.INSERT) {
                columns = ((DmlOperation)operation).columns(typeFactory);
                statementType = Meta.StatementType.IS_DML;
                ((DmlOperation) operation).execute();
            } else {
                ((DdlOperation)operation).execute();
                statementType = Meta.StatementType.OTHER_DDL;
            }

            return new MysqlSignature(columns,
                sql,
                new ArrayList<>(),
                null,
                cursorFactory,
                statementType,
                operation);
        }

        if (sqlNode.getKind().belongsTo(SqlKind.DDL)) {
            DingoDdlVerify.verify(sqlNode, connection);
            final DdlExecutor ddlExecutor = PARSER_CONFIG.parserFactory().getDdlExecutor();
            ddlExecutor.executeDdl(connection, sqlNode);
            return new DingoSignature(
                ImmutableList.of(),
                sql,
                Meta.CursorFactory.OBJECT,
                Meta.StatementType.OTHER_DDL,
                null
            );
        }

        SqlExplain explain = null;
        if (sqlNode.getKind().equals(SqlKind.EXPLAIN)) {
            explain = (SqlExplain) sqlNode;
            sqlNode = explain.getExplicandum();
        }
        SqlValidator validator = getSqlValidator();
        try {
            sqlNode = validator.validate(sqlNode);
        } catch (CalciteContextException e) {
            log.error("Parse and validate error, sql: <[{}]>.", sql, e);
            throw ExceptionUtils.toRuntime(e);
        }
        Meta.StatementType statementType;
        RelDataType type;
        switch (sqlNode.getKind()) {
            case INSERT:
            case DELETE:
            case UPDATE:
                statementType = Meta.StatementType.IS_DML;
                type = RelOptUtil.createDmlRowType(sqlNode.getKind(), typeFactory);
                break;
            default:
                statementType = Meta.StatementType.SELECT;
                type = validator.getValidatedNodeType(sqlNode);
                break;
        }
        RelDataType jdbcType = makeStruct(typeFactory, type);
        List<List<String>> originList = validator.getFieldOrigins(sqlNode);
        final List<ColumnMetaData> columns = getColumnMetaDataList(typeFactory, jdbcType, originList);

        final RelRoot relRoot = convert(sqlNode, false);
        final RelNode relNode = optimize(relRoot.rel);
        extractAutoIncrement(relNode, jobIdPrefix);
        Location currentLocation = MetaService.root().currentLocation();
        RelDataType parasType = validator.getParameterRowType(sqlNode);
        Set<RelOptTable> tables = useTables(relNode, sqlNode);
        boolean isTxn = checkEngine(relNode, sqlNode, tables, connection.getAutoCommit(), connection.getTransaction());
        // get startTs for jobSeqId, if transaction is not null ,transaction startTs is jobDomainId
        long startTs = 0L;
        long jobSeqId = TsoService.getDefault().tso();
        CommonId txn_Id;
        boolean pessimisticTxn = false;
        ITransaction transaction;
        if (connection.getTransaction() != null) {
            transaction = connection.getTransaction();
        } else {
            transaction = connection.createTransaction(isTxn ? OPTIMISTIC : NONE, connection.getAutoCommit());
        }
        startTs = transaction.getStartTs();
        txn_Id = transaction.getTxnId();
        pessimisticTxn = transaction.isPessimistic();
        if (pessimisticTxn) {
            transaction.setForUpdateTs(jobSeqId);
        }
        if (pessimisticTxn && transaction.getPrimaryKeyLock() == null) {
            runPessimisticPrimaryKeyJob(jobSeqId, jobManager, transaction, sqlNode, relNode,
                currentLocation, DefinitionMapper.mapToDingoType(parasType));
            jobSeqId = transaction.getForUpdateTs();
        }
        lockTables(tables, startTs, jobSeqId, transaction.getFinishedFuture());
        Job job = jobManager.createJob(startTs, jobSeqId, txn_Id, DefinitionMapper.mapToDingoType(parasType));
        DingoJobVisitor.renderJob(
            job,
            relNode,
            currentLocation,
            true,
            transaction.getType() == NONE ? null : connection.getTransaction(),
            sqlNode.getKind()
        );
        if (explain != null) {
            statementType = Meta.StatementType.CALL;
            String logicalPlan = RelOptUtil.dumpPlan("", relNode, SqlExplainFormat.TEXT,
                SqlExplainLevel.ALL_ATTRIBUTES);
            return new DingoExplainSignature(
                new ArrayList<>(Collections.singletonList(metaData(typeFactory, 0, "PLAN",
                    new BasicSqlType(RelDataTypeSystem.DEFAULT, SqlTypeName.CHAR), null))),
                sql,
                createParameterList(parasType),
                null,
                cursorFactory,
                statementType,
                sqlNode.toString(),
                logicalPlan,
                job
            );
        }
        return new DingoSignature(
            columns,
            sql,
            createParameterList(parasType),
            null,
            cursorFactory,
            statementType,
            job.getJobId()
        );
    }

    private void lockTables(
        Set<RelOptTable> tables, long startTs, long jobSeqId, CompletableFuture<Void> finishedFuture
    ) {
        if (connection.getLockTables() != null && !connection.getLockTables().isEmpty()) {
            for (RelOptTable table : tables) {
                if (!connection.getLockTables().contains(table.unwrap(DingoTable.class).getTableId())) {
                    throw new RuntimeException("Not lock table: " + table.getQualifiedName());
                }
            }
            return;
        }
        int ttl = Optional.mapOrGet(connection.getClientInfo("lock_wait_timeout"), Integer::parseInt, () -> 50);
        int start = Utils.currentSecond();
        for (RelOptTable table : tables) {
            CompletableFuture<Boolean> lockFuture = new CompletableFuture<>();
            TableLock lock = TableLock.builder()
                .lockTs(startTs)
                .currentTs(jobSeqId)
                .type(LockType.ROW)
                .tableId(table.unwrap(DingoTable.class).getTableId())
                .lockFuture(lockFuture)
                .unlockFuture(finishedFuture)
                .build();
            TableLockService.getDefault().lock(lock);
            int nextTtl = (start + ttl) - Utils.currentSecond();
            if (nextTtl < 0) {
                throw new RuntimeException("Lock wait timeout exceeded.");
            }
            try {
                lockFuture.get(nextTtl, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                lockFuture.cancel(true);
                finishedFuture.complete(null);
                throw new RuntimeException("Lock wait timeout exceeded.");
            } catch (Exception e) {
                lockFuture.cancel(true);
                finishedFuture.complete(null);
                throw ExceptionUtils.toRuntime(e);
            }
        }
    }

    private void runPessimisticPrimaryKeyJob(
        long jobSeqId,
        JobManager jobManager,
        ITransaction transaction,
        SqlNode sqlNode,
        RelNode relNode,
        Location currentLocation,
        DingoType dingoType
    ) {
        Job job = jobManager.createJob(transaction.getStartTs(), jobSeqId, transaction.getTxnId(), dingoType);
        DingoJobVisitor.renderJob(job, relNode, currentLocation, true, transaction, sqlNode.getKind());
        Iterator<Object[]> iterator = jobManager.createIterator(job, null);
        if (iterator.hasNext()) {
            Object[] next = iterator.next();
        }
        jobManager.removeJob(job.getJobId());
    }

    private Set<RelOptTable> useTables(RelNode relNode, SqlNode sqlNode) {
        Set<RelOptTable> tables = RelOptUtil.findTables(relNode);
        if (sqlNode.getKind() == SqlKind.INSERT || sqlNode.getKind() == SqlKind.DELETE) {
            RelNode input = relNode.getInput(0).getInput(0);
            RelOptTable table = input.getTable();
            tables.add(table);
        }
        RelOptTable vectorTable = findVectorFunction(relNode);
        if (vectorTable != null) {
            tables.add(vectorTable);
        }
        return tables;
    }

    private static RelOptTable findVectorFunction(RelNode relNode) {
        RelShuttleImpl relShuttle = new RelShuttleImpl() {
            @Override
            public RelNode visit(RelNode other) {
                if (other instanceof DingoVector) {
                    return other;
                }
                if (other.getInputs().size() > 0) {
                    return visitChildren(other);
                } else {
                    return null;
                }
            }

            protected RelNode visitChildren(RelNode rel) {
                for (Ord<RelNode> input : Ord.zip(rel.getInputs())) {
                    rel = visitChild(input.e);
                }
                return rel;
            }

            private RelNode visitChild(RelNode child) {
                RelNode child2 = child.accept(this);
                if (child2 instanceof DingoVector) {
                    return child2;
                }
                return null;
            }
        };
        RelNode relNode1 = relNode.accept(relShuttle);
        if (relNode1 instanceof DingoVector) {
            DingoVector vector = (DingoVector) relNode1;
            return vector.getTable();
        }
        return null;
    }

    private boolean checkEngine(RelNode relNode, SqlNode sqlNode, Set<RelOptTable> tables, boolean isAutoCommit, ITransaction transaction) {
        boolean isTxn = false;
        boolean isNotTransactionTable = false;
        // for UT test
        if ((sqlNode.getKind() == SqlKind.SELECT || sqlNode.getKind() == SqlKind.DELETE) && tables.size() == 0) {
            return false;
        }
        for (RelOptTable table : tables) {
            String engine = null;
            if (table instanceof RelOptTableImpl) {
                engine = ((DingoTable) ((RelOptTableImpl) table).table()).getTable().getEngine();
            } else if (table instanceof DingoRelOptTable) {
                engine = ((DingoTable) ((DingoRelOptTable) table).table()).getTable().getEngine();
            }
            if (engine == null || !engine.contains("TXN")) {
                isNotTransactionTable = true;
            } else {
                isTxn = true;
            }
            if (isTxn && isNotTransactionTable) {
                throw new RuntimeException("Transactional tables cannot be mixed with non-transactional tables");
            }
            if (transaction != null && transaction.getType() != NONE && isNotTransactionTable) {
                throw new RuntimeException("Non-transaction tables cannot be used in transactions");
            }
        }
        return isTxn;
    }
    /**
     * Determine if it is an insert statement and if there is an autoincrement primary key in the table.
     * @param relNode dingo relNode
     * @param jobIdPrefix Used to distinguish between different SQL statements in the same session
     */
    private void extractAutoIncrement(RelNode relNode, String jobIdPrefix) {
        try {
            RelNode relVal = relNode.accept(AutoIncrementShuttle.INSTANCE);
            if (relVal instanceof DingoValues) {
                DingoValues dingoValues = (DingoValues) relVal;
                if (!dingoValues.isHasAutoIncrement()) {
                    return;
                }
                if (dingoValues.getTuples().size() >= 1 &&
                    dingoValues.getAutoIncrementColIndex() < dingoValues.getTuples().get(0).length) {
                    Object autoValue = dingoValues.getTuples().get(0)[dingoValues.getAutoIncrementColIndex()];
                    if (autoValue != null) {
                        String autoValStr = autoValue.toString();
                        connection.setClientInfo("last_insert_id", autoValStr);
                        connection.setClientInfo(jobIdPrefix, autoValStr);
                        MetaService metaService = MetaService.root();
                        metaService.updateAutoIncrement(dingoValues.getCommonId(), Long.parseLong(autoValStr));
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
