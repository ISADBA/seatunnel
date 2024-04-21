/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.jdbc.internal.executor;

import org.apache.seatunnel.api.table.catalog.TableSchema;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.common.exception.CommonErrorCodeDeprecated;
import org.apache.seatunnel.connectors.seatunnel.jdbc.exception.JdbcConnectorException;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.converter.JdbcRowConverter;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.Function;

@RequiredArgsConstructor
public class DeCycleInsertOrUpdateBatchStatementExecutor
        implements JdbcBatchStatementExecutor<SeaTunnelRow> {
    private final StatementFactory existStmtFactory;
    @NonNull private final StatementFactory insertStmtFactory;
    @NonNull private final StatementFactory updateStmtFactory;
    private final TableSchema keyTableSchema;
    private final Function<SeaTunnelRow, SeaTunnelRow> keyExtractor;
    @NonNull private final TableSchema valueTableSchema;
    @NonNull private final JdbcRowConverter rowConverter;
    private transient PreparedStatement existStatement;
    private transient PreparedStatement insertStatement;
    private transient PreparedStatement updateStatement;

    public DeCycleInsertOrUpdateBatchStatementExecutor(
            StatementFactory insertStmtFactory,
            StatementFactory updateStmtFactory,
            TableSchema valueTableSchema,
            JdbcRowConverter rowConverter) {
        this(
                null,
                insertStmtFactory,
                updateStmtFactory,
                null,
                null,
                valueTableSchema,
                rowConverter);
    }

    @Override
    public void prepareStatements(Connection connection) throws SQLException {
        if (upsertMode()) {
            existStatement = existStmtFactory.createStatement(connection);
        }
        insertStatement = insertStmtFactory.createStatement(connection);
        updateStatement = updateStmtFactory.createStatement(connection);
    }

    @Override
    public void addToBatch(SeaTunnelRow record) throws SQLException {
        boolean exist = existRow(record);
        if (exist) {
            rowConverter.toExternal(valueTableSchema, record, updateStatement);
            updateStatement.addBatch();
            updateStatement.executeBatch();
            updateStatement.clearBatch();
        } else {
            rowConverter.toExternal(valueTableSchema, record, insertStatement);
            insertStatement.addBatch();
            insertStatement.executeBatch();
            insertStatement.clearBatch();
        }
    }

    @Override
    public void executeBatch() throws SQLException {}

    @Override
    public void closeStatements() throws SQLException {
        for (PreparedStatement statement :
                Arrays.asList(existStatement, insertStatement, updateStatement)) {
            if (statement != null) {
                statement.close();
            }
        }
    }

    private boolean upsertMode() {
        return existStmtFactory != null;
    }

    private boolean existRow(SeaTunnelRow record) throws SQLException {
        if (upsertMode()) {
            return exist(keyExtractor.apply(record));
        }
        switch (record.getRowKind()) {
            case INSERT:
                return false;
            case UPDATE_AFTER:
                return true;
            default:
                throw new JdbcConnectorException(
                        CommonErrorCodeDeprecated.UNSUPPORTED_OPERATION,
                        "unsupported row kind: " + record.getRowKind());
        }
    }

    private boolean exist(SeaTunnelRow pk) throws SQLException {
        rowConverter.toExternal(keyTableSchema, pk, existStatement);
        try (ResultSet resultSet = existStatement.executeQuery()) {
            return resultSet.next();
        }
    }

    private void upsertDecycleFlagTable() throws SQLException {
        String decycleFlagSql =
                "INSERT INTO decycle_flag (id, flag) VALUES (1, 0) ON DUPLICATE KEY UPDATE flag = 0";
        insertStatement.executeUpdate(decycleFlagSql);
    }
}
