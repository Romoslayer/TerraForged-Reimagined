package com.terraforged.cereal;

public class CerealSpec {

    public static final char NONE = (char) 0;
    public static final CerealSpec STANDARD = new CerealSpec("  ", CerealSpec.NONE, ' ', '\'');

    public final String indent;
    public final char delimiter;
    public final char separator;
    public final char escapeChar;

    /**
     * @param indent - spaces per indent level
     * @param delimiter - marks the end of a key
     * @param separator - the space between key and value. If the delimiter is NONE the separator must be a space char
     * @param escapeChar - the character use to enclose escaped text
     */
    public CerealSpec(String indent, char delimiter, char separator, char escapeChar) {
        this.indent = indent;
        this.delimiter = delimiter;
        this.escapeChar = escapeChar;
        this.separator = delimiter == NONE ? ' ' : separator;
    }
}
