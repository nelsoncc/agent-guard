package io.agentguard.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ToolCallTest {

    @Test
    void factory_method_sets_fields() {
        ToolCall call = ToolCall.of("id-1", "web_search", Map.of("query", "weather"));
        assertThat(call.id()).isEqualTo("id-1");
        assertThat(call.toolName()).isEqualTo("web_search");
        assertThat(call.arguments()).containsEntry("query", "weather");
        assertThat(call.timestamp()).isNotNull();
    }

    @Test
    void factory_method_without_args_gives_empty_map() {
        ToolCall call = ToolCall.of("id-2", "ping");
        assertThat(call.arguments()).isEmpty();
    }

    @Test
    void builder_sets_raw_input() {
        ToolCall call = ToolCall.builder("id-3", "read_file")
                .rawInput("{\"path\":\"/etc/passwd\"}")
                .argument("path", "/etc/passwd")
                .build();
        assertThat(call.rawInput()).contains("/etc/passwd");
    }

    @Test
    void raw_input_defaults_to_empty_string() {
        ToolCall call = ToolCall.of("id-4", "ping");
        assertThat(call.rawInput()).isEmpty();
    }

    @Test
    void arguments_map_is_immutable() {
        ToolCall call = ToolCall.of("id-5", "tool", Map.of("k", "v"));
        assertThatThrownBy(() -> call.arguments().put("extra", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equality_is_based_on_id_and_tool_name() {
        ToolCall a = ToolCall.of("id-6", "web_search");
        ToolCall b = ToolCall.of("id-6", "web_search");
        ToolCall c = ToolCall.of("id-7", "web_search");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
