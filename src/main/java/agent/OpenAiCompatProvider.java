package agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget-model chat completions over the OpenAI-compatible wire format. The HTTP call is
 * thin - request shape and response parsing are pure, unit-tested methods, so the adapter
 * is verified offline for its contract and only smoke-run against a live endpoint.
 */
public class OpenAiCompatProvider implements Provider {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String baseUrl;

	private final String apiKey;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public OpenAiCompatProvider(String baseUrl, String apiKey) {
		this.baseUrl = stripUserInfo(baseUrl);
		this.apiKey = apiKey;
	}

	/**
	 * Drops any userinfo (user:password) from the configured base URL, so a misconfigured
	 * endpoint cannot echo a credential into an error message. An unparseable URL becomes a
	 * placeholder - the call then fails without naming the endpoint at all.
	 */
	private static String stripUserInfo(String url) {
		try {
			URI uri = URI.create(url);
			if (uri.getUserInfo() == null) {
				return url;
			}
			return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(),
					uri.getFragment()).toString();
		} catch (IllegalArgumentException | URISyntaxException e) {
			return "invalid-endpoint";
		}
	}

	@Override
	public Response complete(Request request) {
		try {
			HttpRequest http = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request)))
					.build();
			HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
			return parseResponse(response.body());
		} catch (Exception e) {
			throw new IllegalStateException("provider call failed: " + e.getMessage(), e);
		}
	}

	/** The exact wire body: model, grounded message turns, and function-calling tools. */
	public static String buildRequestBody(Request request) {
		try {
			ObjectNode body = MAPPER.createObjectNode();
			body.put("model", request.model());
			ArrayNode messages = body.putArray("messages");
			for (Turn turn : request.turns()) {
				ObjectNode message = messages.addObject();
				message.put("role", turn.role());
				message.put("content", turn.text());
				if (!turn.toolCalls().isEmpty()) {
					ArrayNode calls = message.putArray("tool_calls");
					for (ToolCall call : turn.toolCalls()) {
						ObjectNode callNode = calls.addObject();
						callNode.put("type", "function");
						callNode.putObject("function").put("name", call.name())
								.put("arguments", MAPPER.writeValueAsString(call.args()));
					}
				}
			}
			ArrayNode tools = body.putArray("tools");
			for (Map<String, Object> tool : request.tools()) {
				tools.add(MAPPER.valueToTree(tool));
			}
			return MAPPER.writeValueAsString(body);
		} catch (Exception e) {
			throw new IllegalStateException("cannot serialize request", e);
		}
	}

	/** Extracts the assistant message: content text and/or native tool calls. */
	public static Response parseResponse(String body) {
		try {
			JsonNode root = MAPPER.readTree(body);
			JsonNode message = root.path("choices").path(0).path("message");
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode call : message.path("tool_calls")) {
				JsonNode function = call.path("function");
				Map<String, Object> args = new LinkedHashMap<>();
				JsonNode arguments = function.path("arguments");
				if (arguments.isTextual() && !arguments.asText().isBlank()) {
					for (Map.Entry<String, JsonNode> e : MAPPER.readTree(arguments.asText()).properties()) {
						args.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class));
					}
				} else if (arguments.isObject()) {
					args.putAll(MAPPER.convertValue(arguments, Map.class));
				}
				calls.add(new ToolCall(function.path("name").asText(), args));
			}
			String text = message.path("content").isMissingNode() ? null : message.path("content").asText(null);
			return new Response(text, calls);
		} catch (Exception e) {
			throw new IllegalStateException("cannot parse provider response", e);
		}
	}
}
