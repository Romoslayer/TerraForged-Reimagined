package com.terraforged.cereal.serial;

import com.terraforged.cereal.CerealSpec;
import com.terraforged.cereal.value.DataValue;

import java.io.IOException;
import java.io.Writer;

public class DataWriter implements AutoCloseable {

    private final Writer writer;
    private final CerealSpec spec;

    private int indents = 0;
    private boolean newLine = false;

    public DataWriter(Writer writer) {
        this(writer, CerealSpec.STANDARD);
    }

    public DataWriter(Writer writer, CerealSpec spec) {
        this.writer = writer;
        this.spec = spec;
    }

    public void write(DataValue value) throws IOException {
        value.appendTo(this);
    }

    public DataWriter beginObj() throws IOException {
        newLine();
        append('{');
        newLine = true;
        indents++;
        return this;
    }

    public DataWriter endObj() throws IOException {
        indents--;
        newLine();
        append('}');
        newLine = true;
        return this;
    }

    public DataWriter beginList() throws IOException {
        newLine();
        append('[');
        newLine = true;
        indents++;
        return this;
    }

    public DataWriter endList() throws IOException {
        indents--;
        newLine();
        append(']');
        newLine = true;
        return this;
    }

    public DataWriter name(String name) throws IOException {
        newLine();
        append(name);
        append(spec.delimiter);
        append(spec.separator);
        return this;
    }

    public DataWriter type(String name) throws IOException {
        if (!name.isEmpty()) {
            newLine();
            append(name);
            append(spec.separator);
        }
        return this;
    }

    public DataWriter value(Object value) throws IOException {
        if (value instanceof String && escape(value.toString())) {
            append(spec.escapeChar);
            append(value.toString());
            append(spec.escapeChar);
        } else {
            append(value.toString());
        }
        newLine = true;
        return this;
    }

    public DataWriter value(DataValue value) throws IOException {
        value.appendTo(this);
        return this;
    }

    private void append(char c) throws IOException {
        if (c != CerealSpec.NONE) {
            writer.append(c);
        }
    }

    private void append(String string) throws IOException {
        if (string.length() > 0) {
            writer.append(string);
        }
    }

    private void newLine() throws IOException {
        if (newLine && !spec.indent.isEmpty()) {
            append('\n');
            newLine = false;
            indent();
        }
    }

    private void indent() throws IOException {
        if (!spec.indent.isEmpty()) {
            for (int i = 0; i < indents; i++) {
                append(spec.indent);
            }
        }
    }

    private static boolean escape(String in) {
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws Exception {
        writer.close();
    }
}
