package ui.canvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.persist.Names;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node positions are presentation, not flow content: they persist in a side file keyed by
 * the flow name, never inside the document, so a move can never change the content hash or
 * trip the gate. One layout file per flow under the layout directory.
 */
public class LayoutStore {

	public record Pos(double x, double y) {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Path dir;

	public LayoutStore(Path dir) {
		this.dir = dir;
	}

	public void move(String flowName, String nodeId, double x, double y) {
		Path file = Names.safeChild(dir, flowName + ".layout.json");
		try {
			Map<String, Pos> positions = positions(flowName);
			positions.put(nodeId, new Pos(x, y));
			Files.createDirectories(dir);
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<String, Pos> e : positions.entrySet()) {
				Map<String, Object> xy = new LinkedHashMap<>();
				xy.put("x", e.getValue().x());
				xy.put("y", e.getValue().y());
				out.put(e.getKey(), xy);
			}
			MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
		} catch (Exception e) {
			throw new IllegalStateException("cannot persist layout for " + flowName, e);
		}
	}

	public Map<String, Pos> positions(String flowName) {
		Path file = Names.safeChild(dir, flowName + ".layout.json");
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try {
			Map<String, Pos> positions = new LinkedHashMap<>();
			Map<?, ?> raw = MAPPER.readValue(file.toFile(), Map.class);
			for (Map.Entry<?, ?> e : raw.entrySet()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> xy = (Map<String, Object>) e.getValue();
				positions.put(String.valueOf(e.getKey()),
						new Pos(((Number) xy.get("x")).doubleValue(), ((Number) xy.get("y")).doubleValue()));
			}
			return positions;
		} catch (Exception e) {
			throw new IllegalStateException("cannot read layout for " + flowName, e);
		}
	}
}
