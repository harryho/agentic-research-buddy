package agent;

import core.engine.FlowEngine;
import core.model.FlowDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staged writes: the agent never mutates the flow document. Every proposed change lands
 * here as a card; the human's confirm is the only path that applies it - through the same
 * engine edit path a human edit uses, attributed to the agent.
 */
public class AgentDrafts {

	public record Diagnosis(String nodeId, String errorText, FlowDocument fix, String noFixStatement) {

		public boolean hasFix() {
			return fix != null;
		}
	}

	public record Draft(String cardId, FlowDocument edited, Diagnosis diagnosis, String rationale,
			Map<String, String> citations, String baseHash) {
	}

	private final FlowEngine engine;

	private final Map<String, Draft> staged = new LinkedHashMap<>();

	private long counter;

	public AgentDrafts(FlowEngine engine) {
		this.engine = engine;
	}

	public Draft stage(FlowDocument edited, String rationale, Map<String, String> citations) {
		return stage(new Draft(nextCardId(), edited, null, rationale, citations, baseHash()));
	}

	public Draft stageDiagnosis(String nodeId, String errorText, FlowDocument fix, String noFixStatement,
			String rationale) {
		return stage(new Draft(nextCardId(), null,
				new Diagnosis(nodeId, errorText, fix, noFixStatement), rationale, Map.of(), baseHash()));
	}

	private String baseHash() {
		FlowDocument current = engine.document();
		return current == null ? null : current.contentHash();
	}

	public synchronized Draft stage(Draft draft) {
		staged.put(draft.cardId(), draft);
		return draft;
	}

	public synchronized Draft get(String cardId) {
		return staged.get(cardId);
	}

	public synchronized List<String> stagedCards() {
		return new ArrayList<>(staged.keySet());
	}

	public synchronized void discard(String cardId) {
		staged.remove(cardId);
	}

	/** The human confirm: an edit lands through the engine path, attributed to the agent. */
	public synchronized FlowDocument apply(String cardId) {
		Draft draft = staged.remove(cardId);
		if (draft == null) {
			throw new IllegalArgumentException("no such staged card: " + cardId);
		}
		// a human edit between stage and confirm invalidates the staged document - applying it
		// would silently revert that edit, so the card must be re-staged against the new base
		String liveHash = baseHash();
		if (draft.baseHash() != null && liveHash != null && !draft.baseHash().equals(liveHash)) {
			staged.put(cardId, draft);
			throw new IllegalStateException("staged card " + cardId + " is stale: staged against "
					+ draft.baseHash() + " but the flow is now " + liveHash
					+ " - re-stage the proposal against the edited flow");
		}
		if (draft.edited() != null) {
			engine.applyEdit(draft.edited(), "agent");
			return draft.edited();
		}
		if (draft.diagnosis() != null && draft.diagnosis().hasFix()) {
			engine.applyEdit(draft.diagnosis().fix(), "agent");
			return draft.diagnosis().fix();
		}
		return engine.document();
	}

	private String nextCardId() {
		return "agent-" + ++counter;
	}
}
