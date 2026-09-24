package com.terraforged.cereal.value;

import com.terraforged.cereal.serial.DataWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DataList extends DataValue implements Iterable<DataValue> {

    public static final DataList NULL_LIST = new DataList(Collections.emptyList(), false);

    private final boolean nullable;
    private final List<DataValue> data;

    protected DataList(List<DataValue> data, boolean nullable) {
        super(data);
        this.data = data;
        this.nullable = nullable;
    }

    public DataList() {
        this(false);
    }

    public DataList(int size) {
        this(size, false);
    }

    public DataList(boolean nullable) {
        this(16, nullable);
    }

    public DataList(int size, boolean nullable) {
        this(new ArrayList<>(size), nullable);
    }

    public int size() {
        return data.size();
    }

    public boolean contains(Object value) {
        for (DataValue v : this) {
            if (value.equals(v.value)) {
                return true;
            }
        }
        return false;
    }

    public DataValue get(int index) {
        if (index < data.size()) {
            DataValue value = data.get(index);
            if (value != null) {
                return value;
            }
        }
        return DataValue.NULL;
    }

    public DataObject getObj(int index) {
        return get(index).asObj();
    }

    public DataList getList(int index) {
        return get(index).asList();
    }

    public DataList add(Object value) {
        return add(DataValue.of(value));
    }

    public DataList add(DataValue value) {
        if (value.isNonNull() || nullable) {
            data.add(value);
        }
        return this;
    }

    public DataValue set(int index, Object value) {
        return set(index, DataValue.of(value));
    }

    public DataValue set(int index, DataValue value) {
        if (value.isNonNull() || nullable) {
            DataValue removed = data.set(index, value);
            if (removed != null) {
                return removed;
            }
        }
        return DataValue.NULL;
    }

    public DataValue remove(int index) {
        if (index < size()) {
            DataValue value = data.remove(index);
            if (value != null) {
                return value;
            }
        }
        return DataValue.NULL;
    }

    public List<DataValue> getBacking() {
        return data;
    }

    @Override
    public void appendTo(DataWriter writer) throws IOException {
        writer.beginList();
        for (DataValue value : data) {
            writer.value(value);
        }
        writer.endList();
    }

    @Override
    public Iterator<DataValue> iterator() {
        return data.iterator();
    }
}
