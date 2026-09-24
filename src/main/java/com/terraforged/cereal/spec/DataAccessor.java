package com.terraforged.cereal.spec;

import java.util.function.Function;

public interface DataAccessor<T, V> {

    V access(T owner, Context context);

    static <T, V> DataAccessor<T, V> wrap(Function<T, V> func) {
        return (owner, context) -> func.apply(owner);
    }
}
