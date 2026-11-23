package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A directed data edge between two nodes; the scheduler executes targets after their sources. */
public record Wire(String from, String to) {

	@JsonCreator
	public Wire(
			@JsonProperty("from") String from,
			@JsonProperty("to") String to) {
		this.from = from;
		this.to = to;
	}
}
