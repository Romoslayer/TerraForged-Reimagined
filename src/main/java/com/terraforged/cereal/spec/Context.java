package com.terraforged.cereal.spec;

import com.terraforged.cereal.value.DataObject;

public class Context {

    public static final Context NONE = new Context(DataObject.NULL_OBJ);

    private final DataObject data;

    public Context() {
        this(new DataObject());
    }

    public Context(DataObject data) {
        this.data = data;
    }

    public Context skipDefaultValues() {
        data.add("skip_defaults", true);
        return this;
    }

    public boolean skipDefaults() {
        return data.get("skip_defaults").asBool();
    }

    public DataObject getData() {
        return data;
    }
}
