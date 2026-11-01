package com.truesim.scene.gltf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void valuesOfEveryKind() {
        Object value = Json.parse(" { \"a\" : [1, -2.5e1, true, false, null, \"x\"], \"b\": {}, \"c\": [ ] } ");
        assertThat(value).isEqualTo(Map.of("a", java.util.Arrays.asList(1.0, -25.0, true, false, null, "x"),
            "b", Map.of(), "c", List.of()));
        assertThat(Json.parse("\"\\n\\t\\r\\b\\f\\u0041\\\"\\\\\\/\"")).isEqualTo("\n\t\r\b\fA\"\\/");
    }

    @Test
    void whatIsNotJsonIsRefused() {
        for (String text : List.of("", "1 2", "{1: 2}", "{\"a\" 1}", "[1 2]", "tru", "-", "\"open", "\"\\",
            "\"\\u12\"", "{\"a\": 1 \"b\": 2}",
            "{\"a\": 1,", "[1")) {
            assertThatIllegalArgumentException().as(text).isThrownBy(() -> Json.parse(text))
                .withMessageStartingWith("Not JSON");
        }
    }
}
