package core.engine;

import java.time.Instant;

/**
 * One gate audit event: kind is one of proposal, confirm, build, refusal, edit, discard,
 * run-started, run-invalidated. Refusals carry the attempted action and the state at
 * refusal; run-invalidated carries the reason in detail (e.g. superseded).
 */
public record GateEvent(String kind, String action, String actor, String stateAt, String cardId, String detail,
		String at) {

	public static GateEvent of(String kind, String action, String actor, GateState stateAt, String cardId,
			String detail) {
		return new GateEvent(kind, action, actor, stateAt.name(), cardId, detail, Instant.now().toString());
	}
}
