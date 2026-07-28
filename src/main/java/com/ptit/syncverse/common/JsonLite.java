package com.ptit.syncverse.common;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonLite {
    private JsonLite() {
    }

    public static String object(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return json.append('}').toString();
    }

    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '{') {
            return result;
        }
        index++;
        while (index < json.length()) {
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == '}') {
                break;
            }
            ParsedString key = readString(json, index);
            if (key == null) {
                break;
            }
            index = skipWhitespace(json, key.nextIndex());
            if (index >= json.length() || json.charAt(index) != ':') {
                break;
            }
            index = skipWhitespace(json, index + 1);
            String value;
            if (index < json.length() && json.charAt(index) == '"') {
                ParsedString parsedValue = readString(json, index);
                if (parsedValue == null) {
                    break;
                }
                value = parsedValue.value();
                index = parsedValue.nextIndex();
            } else {
                int start = index;
                while (index < json.length() && json.charAt(index) != ',' && json.charAt(index) != '}') {
                    index++;
                }
                value = json.substring(start, index).trim();
            }
            result.put(key.value(), value);
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == ',') {
                index++;
            }
        }
        return result;
    }

    private static ParsedString readString(String json, int index) {
        if (index >= json.length() || json.charAt(index) != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        index++;
        boolean escaped = false;
        while (index < json.length()) {
            char current = json.charAt(index++);
            if (escaped) {
                value.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> current;
                });
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return new ParsedString(value.toString(), index);
            } else {
                value.append(current);
            }
        }
        return null;
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record ParsedString(String value, int nextIndex) {
    }
}
