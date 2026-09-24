package com.terraforged.cereal.spec;

import com.terraforged.cereal.value.DataObject;
import com.terraforged.cereal.value.DataValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SubSpec<T> {
    private final Class<T> superType;
    private final Map<Class<? extends T>, DataSpec<? extends T>> children = new ConcurrentHashMap<>();

    public SubSpec(Class<T> type) {
        this.superType = type;
    }

    public Class<T> getSuperType() {
        return superType;
    }

    public <V extends T> SubSpec<T> register(Class<V> type, DataSpec<V> spec) {
        children.put(type, spec);
        return this;
    }

    public T deserialize(DataObject data, Context context) {
        for (DataSpec<? extends T> spec : children.values()) {
            if (matches(data, spec)) {
                try {
                    return spec.deserialize(data, context);
                } catch (Throwable t) {

                }
            }
        }
        throw new RuntimeException("Unsupported data: " + data);
    }

    public <V extends T> DataValue serialize(V value, Context context) {
        DataSpec<? extends T> spec = children.get(value.getClass());
        if (spec == null) {
            throw new RuntimeException("Missing sub-spec for type: " + value.getClass());
        }
        return spec.serialize(value, context);
    }

    protected static boolean matches(DataObject object, DataSpec<?> spec) {
        for (Map.Entry<String, DefaultData> entry : spec.getDefaults().entrySet()) {
            if (!object.has(entry.getKey())) {
                return false;
            }

            DataValue value = object.get(entry.getKey());
            DataValue defaultValue = entry.getValue().getValue();

            if (!value.matchesType(defaultValue)) {
                return false;
            }
        }
        return true;
    }
}
