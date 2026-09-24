package com.terraforged.cereal.serial;

import java.util.Arrays;

public class DataBuffer {

    private char[] buffer = new char[5];
    private int index = -1;

    private boolean decimal = false;
    private boolean numeric = true;

    public void reset() {
        index = -1;
        numeric = true;
        decimal = false;
    }

    public void append(char c) {
        index++;

        if (index >= buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }

        buffer[index] = c;

        if (numeric) {
            if (Character.isDigit(c)) {
                return;
            }
            if (c == '.' && !decimal && index > 0) {
                decimal = true;
                return;
            }
            if (c == '-' && index == 0) {
                return;
            }
            numeric = false;
        }
    }

    public Object getValue() {
        if (index == 4 && matches(buffer, 4,"true")) {
            return true;
        }
        if (index == 5 && matches(buffer, 5, "false")) {
            return false;
        }
        if (numeric) {
            if (decimal) {
                return parseDouble(buffer, index + 1);
            } else {
                return parseLong(buffer, index + 1);
            }
        }
        return toString();
    }

    @Override
    public String toString() {
        return new String(buffer, 0, index + 1);
    }

    public static boolean matches(char[] buffer, int length, String other) {
        if (length != other.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (Character.toUpperCase(buffer[i]) != Character.toUpperCase(other.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static long parseLong(char[] buffer, int length) {
        long value = 0L;
        boolean negative = false;
        for (int i = 0; i < length; i++) {
            char c = buffer[i];
            if (i == 0 && c == '-') {
                negative = true;
                continue;
            }
            value = (value * 10) + (c - '0');
        }
        return negative ? -value : value;
    }

    public static double parseDouble(char[] buffer, int length) {
        double value = 0;
        int decimalPlace = 0;
        boolean negative = false;

        for (int i = 0; i < length; i++) {
            char c = buffer[i];
            if (i == 0 && c == '-') {
                negative = true;
                continue;
            }
            if (c == '.') {
                decimalPlace = 1;
                continue;
            }
            value = (value * 10) + (c - '0');
            if (decimalPlace > 0) {
                decimalPlace *= 10;
            }
        }

        if (decimalPlace > 0) {
            value /= decimalPlace;
        }

        return negative ? -value : value;
    }
}
