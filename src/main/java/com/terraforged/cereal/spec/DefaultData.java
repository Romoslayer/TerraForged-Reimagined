package com.terraforged.cereal.spec;

import com.terraforged.cereal.value.DataValue;

import java.util.List;
import java.util.function.Supplier;

public class DefaultData {

    private final Class<?> type;
    private final Supplier<DataValue> supplier;

    private DefaultData(Class<?> type, Supplier<DataValue> supplier) {
        this.type = type;
        this.supplier = supplier;
    }

    public DefaultData(Class<?> type, DataValue value) {
        this(type, () -> value);
    }

    public DefaultData(DataValue value) {
        this(() -> value);
    }

    public DefaultData(Supplier<DataValue> supplier) {
        this(Object.class, supplier);
    }

    public boolean hasSpec() {
        return type != Object.class;
    }

    public boolean hasValue() {
        return type == Object.class;
    }

    public List<DataSpec<?>> getSpecs() {
        return DataSpecs.getSpecs(type);
    }

    public DataValue getValue() {
        return supplier.get();
    }
}
