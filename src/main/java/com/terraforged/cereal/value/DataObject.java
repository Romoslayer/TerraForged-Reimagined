package com.terraforged.cereal.value;

import com.terraforged.cereal.serial.DataWriter;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class DataObject extends DataValue implements Iterable<Map.Entry<String, DataValue>> {

    public static final DataObject NULL_OBJ = new DataObject("null", Collections.emptyMap(), false);

    private final String type;
    private final boolean nullable;
    private final Map<String, DataValue> data;

    protected DataObject(String type, Map<String, DataValue> data, boolean nullable) {
        super(data);
        this.type = type;
        this.data = data;
        this.nullable = nullable;
    }

    public DataObject() {
        this("");
    }

    public DataObject(String type) {
        this(type, new LinkedHashMap<>(), false);
    }

    public String getType() {
        return type;
    }

    public int size() {
        return data.size();
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public boolean contains(Object value) {
        for (DataValue v : data.values()) {
            if (value.equals(v.value)) {
                return true;
            }
        }
        return false;
    }

    public DataValue get(String key) {
        return data.getOrDefault(key, NULL);
    }

    public DataObject getObj(String key) {
        return get(key).asObj();
    }

    public DataList getList(String key) {
        return get(key).asList();
    }

    public DataObject add(String key, Object value) {
        return add(key, DataValue.of(value));
    }

    public DataObject add(String key, DataValue value) {
        if (value.isNonNull() || nullable) {
            data.put(key, value);
        }
        return this;
    }

    public DataValue remove(String key) {
        DataValue value = data.remove(key);
        if (value == null) {
            return DataValue.NULL;
        }
        return value;
    }

    public void forEach(BiConsumer<String, DataValue> consumer) {
        data.forEach(consumer);
    }

    public Map<String, DataValue> getBacking() {
        return data;
    }

    @Override
    public void appendTo(DataWriter writer) throws IOException {
        writer.type(type);
        writer.beginObj();
        for (Map.Entry<String, DataValue> entry : data.entrySet()) {
            writer.name(entry.getKey());
            writer.value(entry.getValue());
        }
        writer.endObj();
    }

    @Override
    public Iterator<Map.Entry<String, DataValue>> iterator() {
        return data.entrySet().iterator();
    }
}
