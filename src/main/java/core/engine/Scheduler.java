package core.engine;

import core.model.FlowDocument;
import core.model.Node;
import core.model.Wire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the wire graph into a deterministic execution order: a topological sort of the DAG
 * that breaks ties in document order, so identical flows schedule identically. v1 executes
 * the order sequentially — the determinism law outranks cross-node parallelism, and no
 * acceptance criterion requires concurrent node execution.
 */
public final class Scheduler {

	private Scheduler() {
	}

	public static List<Node> executionOrder(FlowDocument doc) {
		Map<String, List<String>> upstream = new HashMap<>();
		Map<String, Integer> remaining = new HashMap<>();
		for (Node node : doc.nodes()) {
			remaining.put(node.id(), 0);
		}
		for (Wire wire : doc.wires()) {
			if (!remaining.containsKey(wire.from()) || !remaining.containsKey(wire.to())) {
				throw new IllegalStateException("wire references an unknown node: " + wire.from() + " -> " + wire.to());
			}
			upstream.computeIfAbsent(wire.to(), k -> new ArrayList<>()).add(wire.from());
			remaining.merge(wire.to(), 1, Integer::sum);
		}
		List<Node> order = new ArrayList<>();
		List<Node> pending = new ArrayList<>(doc.nodes());
		while (!pending.isEmpty()) {
			boolean placed = false;
			for (int i = 0; i < pending.size(); i++) {
				Node candidate = pending.get(i);
				if (remaining.get(candidate.id()) == 0) {
					order.add(candidate);
					pending.remove(i);
					for (Wire wire : doc.wires()) {
						if (wire.from().equals(candidate.id())) {
							remaining.merge(wire.to(), -1, Integer::sum);
						}
					}
					placed = true;
					break;
				}
			}
			if (!placed) {
				throw new IllegalStateException("flow has a wire cycle involving: " + pending.get(0).id());
			}
		}
		return order;
	}

	/** The execution order truncated after the given target node (inclusive) — run-to-node. */
	public static List<Node> orderUpTo(FlowDocument doc, String targetId) {
		List<Node> order = executionOrder(doc);
		List<Node> truncated = new ArrayList<>();
		for (Node node : order) {
			truncated.add(node);
			if (node.id().equals(targetId)) {
				return truncated;
			}
		}
		throw new IllegalStateException("run-to target is not in the flow: " + targetId);
	}

	/**
	 * Build-time structural rules: every wire endpoint is a known node, no cycles, and each
	 * input port is fed by at most one wire (fan-out is the supported shape; fan-in is a
	 * validation error, reported per offending node).
	 */
	public static void validate(FlowDocument doc) {
		Map<String, Integer> fanIn = new HashMap<>();
		for (Node node : doc.nodes()) {
			fanIn.put(node.id(), 0);
		}
		List<String> violations = new ArrayList<>();
		for (Wire wire : doc.wires()) {
			if (!fanIn.containsKey(wire.from()) || !fanIn.containsKey(wire.to())) {
				violations.add("wire references an unknown node: " + wire.from() + " -> " + wire.to());
				continue;
			}
			fanIn.merge(wire.to(), 1, Integer::sum);
		}
		for (Map.Entry<String, Integer> e : fanIn.entrySet()) {
			if (e.getValue() > 1) {
				violations.add("input port '" + e.getKey() + "' receives " + e.getValue() + " wires; a port accepts one");
			}
		}
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException(String.join("; ", violations));
		}
		executionOrder(doc);
	}
}
