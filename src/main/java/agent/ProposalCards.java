package agent;

import core.model.FlowDocument;
import core.model.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The confirm card's text: what the agent proposes, why, and which grounding each field
 * cites. Deterministic content so the card is testable headless; the Swing panel only
 * renders it with Apply/Decline. Diagnosis cards carry the diagnosis rule: a concrete fix to
 * confirm, or the explicit no-fix statement - never silence.
 */
public final class ProposalCards {

	private ProposalCards() {
	}

	public static String summarize(FlowDocument current, AgentDrafts.Draft draft) {
		if (draft.diagnosis() != null) {
			AgentDrafts.Diagnosis d = draft.diagnosis();
			StringBuilder text = new StringBuilder("diagnosis for node ").append(d.nodeId());
			if (d.errorText() != null && !d.errorText().isEmpty()) {
				text.append(": ").append(d.errorText());
			}
			if (d.hasFix()) {
				text.append("\nproposed fix (confirm to apply): ").append(opSummary(current, d.fix()));
			} else {
				text.append("\nno fix available: ").append(d.noFixStatement());
			}
			return text.toString();
		}
		StringBuilder text = new StringBuilder("proposed change (confirm to apply)");
		List<String> changes = changes(current, draft.edited());
		for (String change : changes) {
			text.append("\n- ").append(change);
		}
		if (draft.rationale() != null && !draft.rationale().isEmpty()) {
			text.append("\nwhy: ").append(draft.rationale());
		}
		for (Map.Entry<String, String> citation : new TreeMap<>(draft.citations()).entrySet()) {
			text.append("\ncites: ").append(citation.getKey()).append(" <- ").append(citation.getValue());
		}
		return text.toString();
	}

	private static List<String> changes(FlowDocument current, FlowDocument edited) {
		List<String> lines = new ArrayList<>();
		Map<String, Node> before = new TreeMap<>();
		for (Node node : current.nodes()) {
			before.put(node.id(), node);
		}
		for (Node node : edited.nodes()) {
			Node old = before.get(node.id());
			if (old == null) {
				lines.add("add node " + node.id() + " (" + node.type() + ")");
				continue;
			}
			if (!old.params().equals(node.params())) {
				Map<String, Object> merged = new TreeMap<>(node.params());
				for (Map.Entry<String, Object> e : merged.entrySet()) {
					if (!e.getValue().equals(old.params().get(e.getKey()))) {
						lines.add(node.id() + ": " + e.getKey() + " = " + e.getValue());
					}
				}
			}
		}
		Map<String, Node> after = new TreeMap<>();
		for (Node node : edited.nodes()) {
			after.put(node.id(), node);
		}
		for (String id : before.keySet()) {
			if (!after.containsKey(id)) {
				lines.add("delete node " + id);
			}
		}
		if (!current.wires().equals(edited.wires())) {
			lines.add("wiring changed");
		}
		return lines;
	}

	private static String opSummary(FlowDocument current, FlowDocument edited) {
		List<String> changes = changes(current, edited);
		return changes.isEmpty() ? "no visible change" : String.join("; ", changes);
	}
}
