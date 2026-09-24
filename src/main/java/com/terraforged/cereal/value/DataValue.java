package com.terraforged.cereal.value;

import com.terraforged.cereal.serial.DataWriter;
import com.terraforged.cereal.spec.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DataValue {

    public static final DataValue NULL = new DataValue(null);

    protected final Object value;

    protected DataValue(Object value) {
        this.value = value;
    }

    public boolean isObj() {
        return this instanceof DataObject;
    }

    public boolean isList() {
        return this instanceof DataList;
    }

    public boolean isNull() {
        return this == NULL;
    }

    public boolean isNonNull() {
        return !isNull();
    }

    public boolean isNum() {
        return value instanceof Number;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public boolean isBool() {
        return value instanceof Boolean;
    }

    public boolean isEnum() {
        return value instanceof Enum<?>;
    }

    public Number asNum() {
        return value instanceof Number ? (Number) value : 0;
    }

    public DataValue inc(int amount) {
        return new DataValue(asInt() + amount);
    }

    public DataValue inc(double amount) {
        return new DataValue(asDouble() + amount);
    }

    public byte asByte() {
        return asNum().byteValue();
    }

    public int asInt() {
        return asNum().intValue();
    }

    public short aShort() {
        return asNum().shortValue();
    }

    public long asLong() {
        return asNum().longValue();
    }

    public float asFloat() {
        return asNum().floatValue();
    }

    public double asDouble() {
        return asNum().doubleValue();
    }

    public boolean asBool() {
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        if (value instanceof String) {
            return value.toString().equalsIgnoreCase("true");
        }
        return asNum().byteValue() == 1;
    }

    public String asString() {
        return value == null ? "null" : value.toString();
    }

    public <E extends Enum<E>> E asEnum(Class<E> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (isString()) {
            return Enum.valueOf(type, asString());
        }
        if (isNum()) {
            int ordinal = asInt();
            E[] values = type.getEnumConstants();
            if (ordinal < values.length) {
                return values[ordinal];
            }
        }
        throw new IllegalArgumentException("Value is not an Enum");
    }

    public DataList asList() {
        return this instanceof DataList ? (DataList) this : DataList.NULL_LIST;
    }

    public DataObject asObj() {
        return this instanceof DataObject ? (DataObject) this : DataObject.NULL_OBJ;
    }

    public DataList toList() {
        return this instanceof DataList ? (DataList) this : new DataList().add(this);
    }

    public <T> T map(Function<DataValue, T> mapper) {
        return mapper.apply(this);
    }

    public <T> Optional<T> map(Predicate<DataValue> predicate, Function<DataValue, T> mapper) {
        return Optional.of(this).filter(predicate).map(mapper);
    }

    public void appendTo(DataWriter writer) throws IOException {
        writer.value(value);
    }

    @Override
    public int hashCode() {
        return value == null ? -1 : value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataValue value1 = (DataValue) o;
        return Objects.equals(value, value1.value);
    }

    @Override
    public String toString() {
        return asString();
    }

    public boolean matchesType(DataValue other) {
        if (getClass() != other.getClass()) {
            return false;
        }

        if (value == null) {
            return other.value == null;
        }

        return other.value != null && value.getClass() == other.value.getClass();
    }

    public static DataValue of(Object value) {
        return of(value, Context.NONE);
    }

    public static DataValue of(Object value, Context context) {
        if (value instanceof DataValue) {
            return (DataValue) value;
        }
        if (value instanceof Number) {
            return new DataValue(value);
        }
        if (value instanceof String) {
            return new DataValue(value);
        }
        if (value instanceof Boolean) {
            return new DataValue(value);
        }
        if (value instanceof Enum<?>) {
            return new DataValue(value);
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            DataList data = new DataList(list.size());
            for (Object o : list) {
                data.add(DataValue.of(o, context));
            }
            return data;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            DataObject data = new DataObject();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                data.add(e.getKey().toString(), DataValue.of(e.getValue(), context));
            }
            return data;
        }
        if (value != null) {
            String name = value.getClass().getSimpleName();
            if (DataSpecs.hasSpec(name)) {
                return DataSpecs.getSpec(name).serialize(value, context);
            }
        }
        return NULL;
    }

    public static Supplier<DataValue> lazy(Object value) {
        return new Supplier<DataValue>() {

            private final Object val = value;
            private DataValue data = null;

            @Override
            public DataValue get() {
                if (data == null) {
                    data = DataValue.of(val);
                }
                return data;
            }
        };
    }
}
