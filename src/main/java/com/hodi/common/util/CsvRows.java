package com.hodi.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader: quoted fields, doubled quotes inside them, commas and line breaks inside quotes,
 * CRLF or LF, and a leading byte-order mark, which Excel writes and nothing else wants.
 *
 * <p>Hand-written rather than a dependency because the whole of it is forty lines and the files it reads
 * are bank statements exported by a person, where the failure that matters is a comma in a payer's name.
 */
public final class CsvRows {

    private CsvRows() {}

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        String s = text.startsWith("﻿") ? text.substring(1) : text;

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> { /* CRLF: the LF that follows ends the row */ }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        // A trailing blank line is not a row.
        rows.removeIf(r -> r.stream().allMatch(v -> v == null || v.isBlank()));
        return rows;
    }

    /** A cell, quoted where it needs to be. */
    public static String escape(String value) {
        if (value == null) return "";
        boolean needs = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needs ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
