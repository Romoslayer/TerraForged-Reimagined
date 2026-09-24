package com.terraforged.cereal;

import com.terraforged.cereal.serial.DataReader;
import com.terraforged.cereal.serial.DataWriter;
import com.terraforged.cereal.spec.*;
import com.terraforged.cereal.value.DataList;
import com.terraforged.cereal.value.DataObject;
import com.terraforged.cereal.value.DataValue;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Cereal {

    public static <T> T read(Reader reader, Class<T> type) throws IOException {
        DataValue data = new DataReader(reader).read();
        return deserialize(data.asObj(), type, Context.NONE);
    }

    public static <T> T read(Reader reader, Class<T> type, Context context) throws IOException {
        DataValue data = new DataReader(reader).read();
        return deserialize(data.asObj(), type, context);
    }

    public static <T> List<T> readList(Reader reader, Class<T> type) throws IOException {
        DataValue data = new DataReader(reader).read();
        return deserialize(data.asList(), type, Context.NONE);
    }

    public static <T> List<T> readList(Reader reader, Class<T> type, Context context) throws IOException {
        DataValue data = new DataReader(reader).read();
        return deserialize(data.asList(), type, context);
    }

    public static void write(Object object, Writer writer) throws IOException {
        write(object, writer, Context.NONE);
    }

    public static void write(Object object, Writer writer, Context context) throws IOException {
        DataWriter dataWriter = new DataWriter(writer);
        DataValue value = serialize(object, context);
        dataWriter.write(value);
    }

    public static void write(Object object, String type, Writer writer) throws IOException {
        write(object, type, writer, Context.NONE);
    }

    public static void write(Object object, String type, Writer writer, Context context) throws IOException {
        DataWriter dataWriter = new DataWriter(writer);
        DataValue value = serialize(type, object, context);
        dataWriter.write(value);
    }

    public static DataValue serialize(Object value) {
        return serialize(value, Context.NONE);
    }

    public static DataValue serialize(Object value, Context context) {
        return serializeInferred(value, context);
    }

    public static DataValue serialize(String type, Object value) {
        return serialize(type, value, Context.NONE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataValue serialize(String type, Object value, Context context) {
        if (DataSpecs.hasSpec(type)) {
            return DataSpecs.getSpec(type).serialize(value, context);
        }

        if (DataSpecs.isSubSpec(value)) {
            SubSpec spec = DataSpecs.getSubSpec(value);
            return spec.serialize(value, context);
        }

        return DataValue.of(value, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataValue serializeInferred(Object value, Context context) {
        if (value.getClass().isArray()) {
            int size = Array.getLength(value);
            DataList list = new DataList();
            for (int i = 0; i < size; i++) {
                list.add(serializeInferred(Array.get(value, i), context));
            }
            return list;
        }

        if (value instanceof Iterable) {
            DataList list = new DataList();
            for (Object child : (Iterable<?>) value) {
                list.add(serializeInferred(child, context));
            }
            return list;
        }

        if (value instanceof Map) {
            DataObject object = new DataObject();
            for (Map.Entry entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() instanceof String) {
                    String key = entry.getKey().toString();
                    DataValue child = serializeInferred(entry.getValue(), context);
                    object.add(key, child);
                }
            }
            return object;
        }

        if (value instanceof SpecName) {
            String name = ((SpecName) value).getSpecName();
            if (DataSpecs.hasSpec(name)) {
                return DataSpecs.getSpec(name).serialize(value, context);
            }
        }

        if (DataSpecs.isSubSpec(value)) {
            SubSpec spec = DataSpecs.getSubSpec(value);
            return spec.serialize(value, context);
        }

        return DataValue.of(value, context);
    }

    public static <T> T deserialize(DataObject data, Class<T> type, Context context) {
        String spec = data.getType();
        if (DataSpecs.hasSpec(spec)) {
            return DataSpecs.getSpec(spec).deserialize(data, type, context);
        }

        SubSpec<?> subSpec = DataSpecs.getSubSpec(type);
        if (subSpec == null) {
            throw new RuntimeException(String.format("No spec registered for name: '%s' or type: '%s'", spec, type));
        }

        return type.cast(subSpec.deserialize(data, context));
    }

    public static <T> List<T> deserialize(DataList data, Class<T> type) {
        return deserialize(data, type, Context.NONE);
    }

    public static <T> List<T> deserialize(DataList data, Class<T> type, Context context) {
        List<T> list = new ArrayList<>(data.size());
        for (DataValue value : data) {
            if (value.isObj()) {
                list.add(deserialize(value.asObj(), type, context));
            }
        }
        return list;
    }
}
