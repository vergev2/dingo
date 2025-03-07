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

package io.dingodb.exec.base;

import io.dingodb.common.CommonId;
import io.dingodb.common.type.DingoType;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;

public interface JobManager {
    Job createJob(long startTs, long jobSeqId, CommonId txnId, DingoType parasType);

    default Job createJob(long startTs, long jobSeqId) {
        return createJob(startTs, jobSeqId, null,  null);
    }

    Job getJob(CommonId jobId);

    void removeJob(CommonId jobId);

    @NonNull Iterator<Object[]> createIterator(@NonNull Job job, Object @Nullable [] paras);

    void close();
}
