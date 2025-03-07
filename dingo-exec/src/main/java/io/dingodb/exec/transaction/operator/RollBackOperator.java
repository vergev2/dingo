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

package io.dingodb.exec.transaction.operator;

import io.dingodb.common.CommonId;
import io.dingodb.common.store.KeyValue;
import io.dingodb.exec.Services;
import io.dingodb.exec.dag.Vertex;
import io.dingodb.exec.fin.Fin;
import io.dingodb.exec.fin.FinWithException;
import io.dingodb.exec.transaction.base.TransactionType;
import io.dingodb.exec.operator.data.Context;
import io.dingodb.exec.transaction.params.RollBackParam;
import io.dingodb.exec.transaction.util.TransactionUtil;
import io.dingodb.exec.utils.ByteUtils;
import io.dingodb.store.api.StoreInstance;
import io.dingodb.store.api.transaction.data.IsolationLevel;
import io.dingodb.store.api.transaction.data.Op;
import io.dingodb.store.api.transaction.data.rollback.TxnBatchRollBack;
import io.dingodb.store.api.transaction.data.rollback.TxnPessimisticRollBack;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class RollBackOperator extends TransactionOperator {
    public static final RollBackOperator INSTANCE = new RollBackOperator();

    private RollBackOperator() {
    }

    @Override
    public synchronized boolean push(Context context, @Nullable Object[] tuple, Vertex vertex) {
        RollBackParam param = vertex.getParam();
        CommonId.CommonType type = CommonId.CommonType.of((byte) tuple[0]);
        CommonId txnId = (CommonId) tuple[1];
        CommonId tableId = (CommonId) tuple[2];
        CommonId newPartId = (CommonId) tuple[3];
        int op = (byte) tuple[4];
        byte[] key = (byte[]) tuple[5];
        long forUpdateTs = 0;
        if (param.getTransactionType() == TransactionType.PESSIMISTIC) {
            StoreInstance store = Services.LOCAL_STORE.getInstance(tableId, newPartId);
            byte[] txnIdByte = txnId.encode();
            byte[] tableIdByte = tableId.encode();
            byte[] partIdByte = newPartId.encode();
            int len = txnIdByte.length + tableIdByte.length + partIdByte.length;
            byte[] lockBytes = ByteUtils.encode(
                CommonId.CommonType.TXN_CACHE_LOCK,
                key,
                Op.LOCK.getCode(),
                len,
                txnIdByte,
                tableIdByte,
                partIdByte);
            KeyValue keyValue = store.get(lockBytes);
            if (keyValue == null) {
                throw new RuntimeException(txnId + " lock keyValue is null ");
            }
            forUpdateTs = (long) ByteUtils.decodePessimisticLock(keyValue)[6];
        }
        CommonId partId = param.getPartId();
        if (partId == null) {
            partId = newPartId;
            param.setPartId(partId);
            param.setTableId(tableId);
            param.addKey(key);
            param.addForUpdateTs(forUpdateTs);
        } else if (partId.equals(newPartId)) {
            param.addKey(key);
            param.addForUpdateTs(forUpdateTs);
            if (param.getKeys().size() == TransactionUtil.max_pre_write_count) {
                boolean result = txnRollBack(
                    param,
                    txnId,
                    tableId,
                    partId,
                    param.getTransactionType() == TransactionType.PESSIMISTIC
                );
                if (!result) {
                    throw new RuntimeException(txnId + " " + partId + ",txnBatchRollback false");
                }
                param.getKeys().clear();
                param.setPartId(null);
            }
        } else {
            boolean result = txnRollBack(
                param,
                txnId,
                tableId,
                partId,
                param.getTransactionType() == TransactionType.PESSIMISTIC
            );
            if (!result) {
                throw new RuntimeException(txnId + " " + partId + ",txnBatchRollback false");
            }
            param.getKeys().clear();
            param.addKey(key);
            param.addForUpdateTs(forUpdateTs);
            param.setPartId(newPartId);
            param.setTableId(tableId);
        }
        return true;
    }

    private boolean txnRollBack(RollBackParam param, CommonId txnId, CommonId tableId, CommonId newPartId, boolean isPessimistic) {
        if (isPessimistic) {
            int isolationLevel = param.getIsolationLevel();
            long startTs = param.getStartTs();
            // call sdk TxnPessimisticRollBack
            for (int i = 0; i < param.getKeys().size(); i++) {
                txnPessimisticRollBack(
                    param.getKeys().get(i),
                    startTs,
                    param.getForUpdateTsList().get(i),
                    isolationLevel,
                    txnId,
                    tableId,
                    newPartId
                );
            }
        }
        // 1、Async call sdk TxnRollBack
        TxnBatchRollBack rollBackRequest = TxnBatchRollBack.builder().
            isolationLevel(IsolationLevel.of(param.getIsolationLevel()))
            .startTs(param.getStartTs())
            .keys(param.getKeys())
            .build();
        try {
            StoreInstance store = Services.KV_STORE.getInstance(tableId, newPartId);
            return store.txnBatchRollback(rollBackRequest);
        } catch (RuntimeException e) {
            log.error(e.getMessage(), e);
            // 2、regin split
            Map<CommonId, List<byte[]>> partMap = TransactionUtil.multiKeySplitRegionId(tableId, txnId, param.getKeys());
            for (Map.Entry<CommonId, List<byte[]>> entry : partMap.entrySet()) {
                CommonId regionId = entry.getKey();
                List<byte[]> value = entry.getValue();
                StoreInstance store = Services.KV_STORE.getInstance(tableId, regionId);
                rollBackRequest.setKeys(value);
                boolean result = store.txnBatchRollback(rollBackRequest);
                if (!result) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean txnPessimisticRollBack(byte[] key, long startTs, long forUpdateTs, int isolationLevel,
                                           CommonId txnId, CommonId tableId, CommonId newPartId) {
        // 1、Async call sdk TxnPessimisticRollBack
        TxnPessimisticRollBack pessimisticRollBack = TxnPessimisticRollBack.builder()
            .isolationLevel(IsolationLevel.of(isolationLevel))
            .startTs(startTs)
            .forUpdateTs(forUpdateTs)
            .keys(Collections.singletonList(key))
            .build();
        try {
            StoreInstance store = Services.KV_STORE.getInstance(tableId, newPartId);
            return store.txnPessimisticLockRollback(pessimisticRollBack);
        } catch (RuntimeException e) {
            log.error(e.getMessage(), e);
            // 2、regin split
            Map<CommonId, List<byte[]>> partMap = TransactionUtil.multiKeySplitRegionId(
                tableId,
                txnId,
                Collections.singletonList(key)
            );
            for (Map.Entry<CommonId, List<byte[]>> entry : partMap.entrySet()) {
                CommonId regionId = entry.getKey();
                List<byte[]> value = entry.getValue();
                StoreInstance store = Services.KV_STORE.getInstance(tableId, regionId);
                pessimisticRollBack.setKeys(value);
                boolean result = store.txnPessimisticLockRollback(pessimisticRollBack);
                if (!result) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public synchronized void fin(int pin, @Nullable Fin fin, Vertex vertex) {
        if (!(fin instanceof FinWithException)) {
            RollBackParam param = vertex.getParam();
            if (param.getKeys().size() > 0) {
                CommonId txnId = vertex.getTask().getTxnId();
                boolean result = txnRollBack(
                    param,
                    txnId,
                    param.getTableId(),
                    param.getPartId(),
                    param.getTransactionType() == TransactionType.PESSIMISTIC
                );
                if (!result) {
                    throw new RuntimeException(txnId + " " + param.getPartId() + ",txnBatchRollback false");
                }
                param.getKeys().clear();
            }
            vertex.getSoleEdge().transformToNext(new Object[]{true});
        }
        vertex.getSoleEdge().fin(fin);
    }

}
