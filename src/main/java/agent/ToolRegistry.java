package agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-house tool SPI: named, typed, self-describing tools the provider calls through
 * native function-calling. This registry is the seam a future MCP or R bridge plugs into -
 * the loop and the provider adapters never learn about individual tools.
 */
public class ToolRegistry {

	public interface Tool {

		String name();

		String description();

		Map<String, String> params();

		ToolResult execute(Map<String, Object> args);
	}

	private final Map<String, Tool> tools = new ConcurrentHashMap<>();

	public void register(Tool tool) {
		tools.put(tool.name(), tool);
	}

	public Tool get(String name) {
		return tools.get(name);
	}

	public List<Tool> tools() {
		return tools.values().stream().sorted(java.util.Comparator.comparing(Tool::name)).toList();
	}

	/** Provider function-calling specs, derived from the tools' own descriptions. */
	public List<Map<String, Object>> schemas() {
		List<Map<String, Object>> schemas = new ArrayList<>();
		for (Tool tool : tools()) {
			Map<String, Object> function = new LinkedHashMap<>();
			function.put("name", tool.name());
			function.put("description", tool.description());
			Map<String, Object> parameters = new LinkedHashMap<>();
			parameters.put("type", "object");
			Map<String, Object> properties = new LinkedHashMap<>();
			for (Map.Entry<String, String> param : tool.params().entrySet()) {
				Map<String, Object> property = new LinkedHashMap<>();
				property.put("type", param.getValue());
				properties.put(param.getKey(), property);
			}
			parameters.put("properties", properties);
			function.put("parameters", parameters);
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "function");
			schema.put("function", function);
			schemas.add(schema);
		}
		return schemas;
	}
}
