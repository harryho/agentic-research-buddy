package core.engine;

import java.time.Instant;

/**
 * A user confirm event, minted only by the gate's user-action entries. The constructor is
 * deliberately package-private: no code outside this package — the agent module in
 * particular — can bring one into existence.
 */
public final class ConfirmEvent {

	private final String cardId;

	private final String actor;

	private final String at;

	ConfirmEvent(String cardId, String actor) {
		this.cardId = cardId;
		this.actor = actor;
		this.at = Instant.now().toString();
	}

	public String cardId() {
		return cardId;
	}

	public String actor() {
		return actor;
	}

	public String at() {
		return at;
	}
}
