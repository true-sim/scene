package com.truesim.scene.gltf;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for glTF documents: objects become {@code Map<String, Object>}, arrays {@code List<Object>},
 * numbers {@code Double}, and strings, booleans and null themselves.
 */
@AllowAllocation("parsing documents when assets load")
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** {@code text}'s value; throws {@link IllegalArgumentException} where it is not JSON. */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("text after the value");
        }
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw error("a value");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) {
            return object;
        }
        while (true) {
            space();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw error("a name in quotes");
            }
            String name = string();
            space();
            expect(':');
            object.put(name, value());
            space();
            if (peek('}')) {
                return object;
            }
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        at++;
        space();
        if (peek(']')) {
            return array;
        }
        while (true) {
            array.add(value());
            space();
            if (peek(']')) {
                return array;
            }
            expect(',');
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("the string's end");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error("an escape");
            }
            char e = text.charAt(at++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw error("four hex digits");
                    }
                    out.append((char) Integer.parseInt(text, at, at + 4, 16));
                    at += 4;
                }
                default -> out.append(e); // \" \\ \/
            }
        }
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error(word);
        }
        at += word.length();
        return value;
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            at = start;
            throw error("a value");
        }
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("'" + c + "'");
        }
    }

    private IllegalArgumentException error(String expected) {
        return new IllegalArgumentException("Not JSON: " + expected + " expected at " + at);
    }
}
