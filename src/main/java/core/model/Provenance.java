package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How a flow came to exist. Kind is one of: hand-built (canvas or document API), agent-built
 * (materialized from a confirmed summary card), imported (external adapter). Timestamps are
 * ISO-8601 strings so the JSON manifest stays byte-stable across JDK versions.
 */
public record Provenance(String kind, String author, String createdAt) {

	@JsonCreator
	public Provenance(
			@JsonProperty("kind") String kind,
			@JsonProperty("author") String author,
			@JsonProperty("createdAt") String createdAt) {
		this.kind = kind;
		this.author = author;
		this.createdAt = createdAt;
	}
}
