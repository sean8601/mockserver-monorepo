/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.mockserver.openapi.examples.models.*;

import java.io.IOException;
import java.util.List;

/**
 * See: https://github.com/swagger-api/swagger-inflector
 */
public class JsonNodeExampleSerializer extends JsonSerializer<Example> {

    @Override
    public void serialize(Example value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {

        if (value instanceof ObjectExample obj) {
            jsonGenerator.writeStartObject();
            writeTo(jsonGenerator, obj);
            jsonGenerator.writeEndObject();
        } else if (value instanceof ArrayExample obj) {
            jsonGenerator.writeStartArray();
            for (Example item : obj.getItems()) {
                if (item instanceof ObjectExample) {
                    jsonGenerator.writeStartObject();
                }
                writeTo(jsonGenerator, item);
                if (item instanceof ObjectExample) {
                    jsonGenerator.writeEndObject();
                }
            }
            jsonGenerator.writeEndArray();
        } else {
            writeTo(jsonGenerator, value);
        }
    }

    public void writeTo(JsonGenerator jsonGenerator, Example o) throws IOException {
        if (o instanceof ObjectExample obj) {
            for (String key : obj.keySet()) {
                Example value = (Example) obj.get(key);
                writeValue(jsonGenerator, key, value);
            }
        } else if (o instanceof ArrayExample arrayExample) {
            jsonGenerator.writeStartArray();
            List<Example> items = arrayExample.getItems();
            for (Example item : items) {
                serialize(item, jsonGenerator, null);
            }
            jsonGenerator.writeEndArray();
        } else {
            writeValue(jsonGenerator, null, o);
        }
    }

    public void writeValue(JsonGenerator jsonGenerator, String field, Example o) throws IOException {
        if (o instanceof ArrayExample obj) {
            jsonGenerator.writeArrayFieldStart(field);
            for (Example item : obj.getItems()) {
                if (item instanceof ObjectExample) {
                    jsonGenerator.writeStartObject();
                    writeTo(jsonGenerator, item);
                    jsonGenerator.writeEndObject();
                } else {
                    writeTo(jsonGenerator, item);
                }
            }
            jsonGenerator.writeEndArray();
        } else if (o instanceof BooleanExample obj) {
            if (field != null) {
                jsonGenerator.writeBooleanField(field, obj.getValue());
            } else {
                jsonGenerator.writeBoolean(obj.getValue());
            }
        } else if (o instanceof DecimalExample obj) {
            if (field != null) {
                jsonGenerator.writeNumberField(field, obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof BigIntegerExample obj) {
            if (field != null) {
                jsonGenerator.writeFieldName(field);
                jsonGenerator.writeNumber(obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof DoubleExample obj) {
            if (field != null) {
                jsonGenerator.writeNumberField(field, obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof FloatExample obj) {
            if (field != null) {
                jsonGenerator.writeNumberField(field, obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof IntegerExample obj) {
            if (field != null) {
                jsonGenerator.writeNumberField(field, obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof LongExample obj) {
            if (field != null) {
                jsonGenerator.writeNumberField(field, obj.getValue());
            } else {
                jsonGenerator.writeNumber(obj.getValue());
            }
        } else if (o instanceof ObjectExample obj) {
            if (field != null) {
                jsonGenerator.writeObjectField(field, obj);
            }
        } else if (o instanceof StringExample obj) {
            if (field != null) {
                jsonGenerator.writeStringField(field, obj.getValue());
            } else {
                jsonGenerator.writeString(obj.getValue());
            }
        }
    }

    @Override
    public Class<Example> handledType() {
        return Example.class;
    }
}