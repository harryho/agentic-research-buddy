package agent;

/**
 * Every tool returns a typed result: success with a summary and data payload, or a refusal
 * with an explicit reason. Refusals are data, not exceptions - the agent loop reports them
 * to the model and the chat.
 */
public record ToolResult(boolean ok, String summary, Object data, String refusalReason) {

	public static ToolResult ok(String summary, Object data) {
		return new ToolResult(true, summary, data, null);
	}

	public static ToolResult refused(String reason) {
		return new ToolResult(false, "refused", null, reason);
	}
}
