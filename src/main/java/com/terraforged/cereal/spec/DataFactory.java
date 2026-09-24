package com.terraforged.cereal.spec;

import com.terraforged.cereal.value.DataObject;

public interface DataFactory<T> {

    T create(DataObject data, DataSpec<T> spec, Context context);
}
