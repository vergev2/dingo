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

package io.dingodb.exec.expr;

import io.dingodb.expr.runtime.TupleEvalContext;
import lombok.Setter;
import org.checkerframework.checker.nullness.qual.NonNull;

public class DingoEvalContext implements TupleEvalContext {
    private static final long serialVersionUID = -5336801318047905367L;

    private final ThreadLocal<Object[]> tuple = new ThreadLocal<>();

    @Setter
    private Object[] paras = null;

    @Override
    public void setTuple(Object @NonNull [] tuple) {
        this.tuple.set(tuple);
    }

    @Override
    public Object get(Object id) {
        int index = (Integer) id;
        if (index >= 0) {
            return tuple.get()[index];
        }
        // id < 0 means it is a sql parameter.
        assert paras != null : "Parameters are not available in this context.";
        return paras[-index - 1];
    }

    @Override
    public void set(Object id, Object value) {
        int index = (Integer) id;
        if (index >= 0) {
            tuple.get()[index] = value;
        }
        // id < 0 means it is a sql parameter.
        assert paras != null : "Parameters are not available in this context.";
        paras[-index - 1] = value;
    }
}
