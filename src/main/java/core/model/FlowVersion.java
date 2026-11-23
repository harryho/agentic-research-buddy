package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** An immutable snapshot of a flow document at a point in its version history. */
public record FlowVersion(int number, FlowDocument document, String createdAt) {

	@JsonCreator
	public FlowVersion(
			@JsonProperty("number") int number,
			@JsonProperty("document") FlowDocument document,
			@JsonProperty("createdAt") String createdAt) {
		this.number = number;
		this.document = document;
		this.createdAt = createdAt;
	}
}
