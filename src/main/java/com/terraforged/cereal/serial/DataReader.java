package com.terraforged.cereal.serial;

import com.terraforged.cereal.CerealSpec;
import com.terraforged.cereal.value.DataList;
import com.terraforged.cereal.value.DataObject;
import com.terraforged.cereal.value.DataValue;

import java.io.IOException;
import java.io.Reader;

public class DataReader implements AutoCloseable {

    private static final char NONE = (char) -1;

    private final Reader reader;
    private final CerealSpec spec;
    private final DataBuffer buffer = new DataBuffer();

    private char c = NONE;

    public DataReader(Reader reader) {
        this(reader, CerealSpec.STANDARD);
    }

    public DataReader(Reader reader, CerealSpec spec) {
        this.reader = reader;
        this.spec = spec;
    }

    private boolean next() throws IOException {
        int i = reader.read();
        if (i == -1) {
            return false;
        }
        c = (char) i;
        return true;
    }

    private void skipSpace() throws IOException {
        while (Character.isWhitespace(c)) {
            if (!next()) {
                throw new IOException("Unexpected end");
            }
        }
    }

    public DataValue read() throws IOException {
        if (next()) {
            return readValue();
        }
        return DataValue.NULL;
    }

    private DataValue readValue() throws IOException {
        skipSpace();

        if (c == '{' && next()) {
            return readObject("");
        }

        if (c == '[' && next()) {
            return readList();
        }

        Object value = readPrimitive();
        if (value instanceof String) {
            skipSpace();
            if (c == '{' && next()) {
                return readObject(value.toString());
            }
        }

        return DataValue.of(value);
    }

    private DataValue readObject(String type) throws IOException {
        DataObject data = new DataObject(type);
        while (true) {
            skipSpace();
            if (c == '}') {
                break;
            }
            String key = readKey();
            DataValue value = readValue();
            data.add(key, value);
        }
        next();
        return data;
    }

    private DataValue readList() throws IOException {
        DataList list = new DataList();
        while (true) {
            skipSpace();
            if (c == ']') {
                break;
            }
            list.add(readValue());
        }
        next();
        return list;
    }

    private String readKey() throws IOException {
        skipSpace();
        buffer.reset();
        buffer.append(c);
        while (true) {
            if (!next()) {
                throw new IOException("Unexpected end: " + buffer.toString());
            }

            if (!Character.isLetterOrDigit(c) && c != '_') {
                if (c == ':') {
                    next();
                }
                break;
            }

            buffer.append(c);
        }
        return buffer.toString();
    }

    private Object readPrimitive() throws IOException {
        if (c == spec.escapeChar) {
            return readEscapedString();
        }

        buffer.reset();
        buffer.append(c);
        while (true) {
            if (!next()) {
                throw new IOException("Unexpected end of string: " + buffer.toString());
            }

            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                break;
            }

            buffer.append(c);
        }

        return buffer.getValue();
    }

    private String readEscapedString() throws IOException {
        buffer.reset();
        while (true) {
            if (!next()) {
                throw new IOException("Unexpected end of string: " + buffer.toString());
            }

            if (c == spec.escapeChar) {
                next();
                break;
            }

            buffer.append(c);
        }
        return buffer.toString();
    }

    @Override
    public void close() throws Exception {
        reader.close();
    }
}
