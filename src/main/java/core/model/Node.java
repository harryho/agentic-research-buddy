package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One node in a flow: an id unique in the document, a registry type, and its parameters. */
public record Node(String id, String type, Map<String, Object> params) {

	@JsonCreator
	public Node(
			@JsonProperty("id") String id,
			@JsonProperty("type") String type,
			@JsonProperty("params") Map<String, Object> params) {
		this.id = id;
		this.type = type;
		this.params = params == null
				? Collections.unmodifiableMap(new LinkedHashMap<>())
				: Collections.unmodifiableMap(new LinkedHashMap<>(params));
	}
}
