/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.samza.sql.avro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.samza.SamzaException;
import org.apache.samza.config.Config;
import org.apache.samza.operators.KV;
import org.apache.samza.sql.data.SamzaSqlRelMessage;
import org.apache.samza.sql.interfaces.SamzaRelConverter;
import org.apache.samza.system.SystemStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.samza.sql.data.SamzaSqlRelMessage.SamzaSqlRelRecord;


/**
 * This class converts a Samza Avro messages to Relational messages and vice versa.
 * This supports Samza messages where Key is a string and Value is an avro record.
 *
 * Conversion from Samza to Relational Message :
 *     The key part of the samza message is represented as a special column {@link SamzaSqlRelMessage#KEY_NAME}
 *     in relational message.
 *
 *     The value part of the samza message is expected to be {@link IndexedRecord}, All the fields in the IndexedRecord
 *     form the corresponding fields of the relational message.
 *
 * Conversion from Relational to Samza Message :
 *     This converts the Samza relational message into Avro {@link GenericRecord}.
 *     All the fields of the relational message become fields of the Avro GenericRecord except the field with name
 *     {@link SamzaSqlRelMessage#KEY_NAME}. This special field becomes the Key in the output Samza message.
 */
public class AvroRelConverter implements SamzaRelConverter {

  protected final Config config;
  private final Schema avroSchema;

  private static final Logger LOG = LoggerFactory.getLogger(AvroRelConverter.class);

  public AvroRelConverter(SystemStream systemStream, AvroRelSchemaProvider schemaProvider, Config config) {
    this.config = config;
    this.avroSchema = Schema.parse(schemaProvider.getSchema(systemStream));
  }

  /**
   * Converts the nested avro object in SamzaMessage to relational message corresponding to
   * the tableName with relational schema.
   */
  @Override
  public SamzaSqlRelMessage convertToRelMessage(KV<Object, Object> samzaMessage) {
    List<Object> fieldValues = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();
    Object value = samzaMessage.getValue();
    if (value instanceof IndexedRecord) {
      IndexedRecord record = (IndexedRecord) value;
      fieldNames.addAll(avroSchema.getFields().stream()
          .map(Schema.Field::name)
          .collect(Collectors.toList()));
      fieldValues.addAll(fieldNames.stream()
          .map(f -> convertToJavaObject(record.get(avroSchema.getField(f).pos()), avroSchema.getField(f).schema()))
          .collect(Collectors.toList()));
    } else if (value == null) {
      fieldNames.addAll(avroSchema.getFields().stream().map(Schema.Field::name).collect(Collectors.toList()));
      IntStream.range(0, fieldNames.size()).forEach(x -> fieldValues.add(null));
    } else {
      String msg = "Avro message converter doesn't support messages of type " + value.getClass();
      LOG.error(msg);
      throw new SamzaException(msg);
    }

    return new SamzaSqlRelMessage(samzaMessage.getKey(), fieldNames, fieldValues);
  }

  private SamzaSqlRelRecord convertToRelRecord(IndexedRecord avroRecord) {
    List<Object> values = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();
    if (avroRecord != null) {
      fieldNames.addAll(avroRecord.getSchema().getFields()
          .stream()
          .map(Schema.Field::name)
          .collect(Collectors.toList()));
      values.addAll(avroRecord.getSchema().getFields()
          .stream()
          .map(f -> convertToJavaObject(avroRecord.get(avroRecord.getSchema().getField(f.name()).pos()),
              avroRecord.getSchema().getField(f.name()).schema()))
          .collect(Collectors.toList()));
    } else {
      String msg = "Avro Record is null";
      LOG.error(msg);
      throw new SamzaException(msg);
    }

    return new SamzaSqlRelRecord(fieldNames, values);
  }

  /**
   * Convert the nested relational message to the output samza message.
   */
  @Override
  public KV<Object, Object> convertToSamzaMessage(SamzaSqlRelMessage relMessage) {
    return convertToSamzaMessage(relMessage, this.avroSchema);
  }

  protected KV<Object, Object> convertToSamzaMessage(SamzaSqlRelMessage relMessage, Schema avroSchema) {
    return new KV<>(relMessage.getKey(), convertToGenericRecord(relMessage.getSamzaSqlRelRecord(), avroSchema));
  }

  private GenericRecord convertToGenericRecord(SamzaSqlRelRecord relRecord, Schema schema) {
    GenericRecord record = new GenericData.Record(schema);
    List<String> fieldNames = relRecord.getFieldNames();
    List<Object> values = relRecord.getFieldValues();
    for (int index = 0; index < fieldNames.size(); index++) {
      if (!fieldNames.get(index).equalsIgnoreCase(SamzaSqlRelMessage.KEY_NAME)) {
        Object relObj = values.get(index);
        String fieldName = fieldNames.get(index);
        Schema fieldSchema = schema.getField(fieldName).schema();
        record.put(fieldName, convertToAvroObject(relObj, fieldSchema));
      }
    }
    return record;
  }

  private Object convertToAvroObject(Object relObj, Schema schema) {
    if (relObj == null) {
      return null;
    }
    switch(schema.getType()) {
      case RECORD:
        return convertToGenericRecord((SamzaSqlRelRecord) relObj, getNonNullUnionSchema(schema));
      case ARRAY:
        List<Object> avroList = ((List<Object>) relObj).stream()
            .map(o -> convertToAvroObject(o, getNonNullUnionSchema(schema).getElementType()))
            .collect(Collectors.toList());
        return avroList;
      case MAP:
        return ((Map<String, ?>) relObj).entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> convertToAvroObject(e.getValue(),
                getNonNullUnionSchema(schema).getValueType())));
      case UNION:
        return convertToAvroObject(relObj, getNonNullUnionSchema(schema));
      default:
        return relObj;
    }
  }

  // Not doing any validations of data types with Avro schema considering the resource cost per message.
  // Casting would fail if the data types are not in sync with the schema.
  private Object convertToJavaObject(Object avroObj, Schema schema) {
    switch(schema.getType()) {
      case RECORD:
        if (avroObj == null) {
          return null;
        }
        return convertToRelRecord((IndexedRecord) avroObj);
      case ARRAY: {
        ArrayList<Object> retVal = new ArrayList<>();
        if (avroObj != null) {
          List<Object> avroArray = null;
          if (avroObj instanceof GenericData.Array) {
            avroArray = (GenericData.Array) avroObj;
          } else if (avroObj instanceof List) {
            avroArray = (List) avroObj;
          }

          if (avroArray != null) {
            retVal.addAll(
                avroArray.stream()
                    .map(v -> convertToJavaObject(v, getNonNullUnionSchema(schema).getElementType()))
                    .collect(Collectors.toList()));
          }
        }
        return retVal;
      }
      case MAP: {
        Map<String, Object> retVal = new HashMap<>();
        if (avroObj != null) {
          retVal.putAll(((Map<String, ?>) avroObj).entrySet().stream()
              .collect(Collectors.toMap(
                  Map.Entry::getKey,
                  e -> convertToJavaObject(e.getValue(), getNonNullUnionSchema(schema).getValueType()))));
        }
        return retVal;
      }
      case UNION:
        if (avroObj == null) {
          return null;
        }
        return convertToJavaObject(avroObj, getNonNullUnionSchema(schema));
      default:
        return avroObj;
    }
  }

  // Two non-nullable types in a union is not yet supported.
  private Schema getNonNullUnionSchema(Schema schema) {
    if (schema.getType().equals(Schema.Type.UNION)) {
      if (schema.getTypes().get(0).getType() != Schema.Type.NULL) {
        return schema.getTypes().get(0);
      }
      if (schema.getTypes().get(1).getType() != Schema.Type.NULL) {
        return schema.getTypes().get(1);
      }
    }
    return schema;
  }
}
