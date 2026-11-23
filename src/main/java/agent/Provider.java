package agent;

import java.util.List;
import java.util.Map;

/**
 * The provider seam: one turn-based, native function-calling contract that both the
 * OpenAI-compatible HTTP adapter and the offline fake implement. The loop and the studio
 * never learn which one is behind it - switching is configuration, not code.
 */
public interface Provider {

	record ToolCall(String name, Map<String, Object> args) {
	}

	/** role: system | user | assistant | tool. Assistant turns may carry tool calls. */
	record Turn(String role, String text, List<ToolCall> toolCalls) {

		public static Turn of(String role, String text) {
			return new Turn(role, text, List.of());
		}
	}

	record Request(String model, List<Turn> turns, List<Map<String, Object>> tools) {
	}

	record Response(String text, List<ToolCall> toolCalls) {

		public static Response text(String text) {
			return new Response(text, List.of());
		}

		public Response withToolCalls(ToolCall... calls) {
			return new Response(text, List.of(calls));
		}
	}

	Response complete(Request request);
}
