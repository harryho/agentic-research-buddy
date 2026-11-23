package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One audit row in the flow's confirm history: kind is one of proposal, confirm, build,
 * refusal, edit, discard, run-invalidated, validation. Refusals and edits carry the acting
 * actor, so agent writes are attributed exactly like human ones; detail carries the reason
 * where one exists (e.g. superseded). The gate's state derives from these rows on reload.
 */
public record ConfirmHistoryEntry(String kind, String action, String actor, String cardId, String stateAt, String at,
		String detail) {

	@JsonCreator
	public ConfirmHistoryEntry(
			@JsonProperty("kind") String kind,
			@JsonProperty("action") String action,
			@JsonProperty("actor") String actor,
			@JsonProperty("cardId") String cardId,
			@JsonProperty("stateAt") String stateAt,
			@JsonProperty("at") String at,
			@JsonProperty("detail") String detail) {
		this.kind = kind;
		this.action = action;
		this.actor = actor;
		this.cardId = cardId;
		this.stateAt = stateAt;
		this.at = at;
		this.detail = detail;
	}
}
