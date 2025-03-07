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

package io.dingodb.proxy.handler;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.dingodb.sdk.service.entity.common.ScalarField;
import io.dingodb.sdk.service.entity.common.ScalarField.DataNest.StringData;

import java.io.IOException;

public class StringDataSerializer extends JsonSerializer<StringData> {
    @Override
    public void serialize(
        StringData data, JsonGenerator jsonGenerator, SerializerProvider serializerProvider
    ) throws IOException {
        jsonGenerator.writeString(data.getValue());
    }
}
