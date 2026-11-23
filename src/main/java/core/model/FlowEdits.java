package core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of flow mutation: set a parameter, re-wire a port, delete a node.
 * The canvas adapter and the agent's propose_flow tool both go through these, so a hand
 * edit and an agent edit cannot drift apart.
 */
public final class FlowEdits {

	private FlowEdits() {
	}

	public static FlowDocument setParameter(FlowDocument doc, String nodeId, String param, Object value) {
		Node target = node(doc, nodeId);
		if (target == null) {
			throw new IllegalArgumentException("no such node: " + nodeId);
		}
		Map<String, Object> params = new LinkedHashMap<>(target.params());
		params.put(param, value);
		Node replacement = new Node(target.id(), target.type(), Map.copyOf(params));
		List<Node> nodes = doc.nodes().stream()
				.map(n -> n.id().equals(nodeId) ? replacement : n)
				.toList();
		return new FlowDocument(doc.name(), doc.version(), nodes, doc.wires(), doc.seed(), doc.provenance(),
				doc.threadId(), doc.confirmHistory());
	}

	public static FlowDocument rewire(FlowDocument doc, String fromId, String toId) {
		if (node(doc, fromId) == null || node(doc, toId) == null) {
			throw new IllegalArgumentException("wire references unknown nodes: " + fromId + " -> " + toId);
		}
		if (fromId.equals(toId)) {
			throw new IllegalArgumentException("a node cannot wire to itself: " + fromId);
		}
		List<Wire> wires = new ArrayList<>(doc.wires().stream()
				.filter(w -> !w.to().equals(toId))
				.toList());
		wires.add(new Wire(fromId, toId));
		return new FlowDocument(doc.name(), doc.version(), doc.nodes(), List.copyOf(wires), doc.seed(),
				doc.provenance(), doc.threadId(), doc.confirmHistory());
	}

	public static FlowDocument deleteNode(FlowDocument doc, String nodeId) {
		if (node(doc, nodeId) == null) {
			throw new IllegalArgumentException("no such node: " + nodeId);
		}
		List<Node> nodes = doc.nodes().stream().filter(n -> !n.id().equals(nodeId)).toList();
		List<Wire> wires = doc.wires().stream()
				.filter(w -> !w.from().equals(nodeId) && !w.to().equals(nodeId))
				.toList();
		return new FlowDocument(doc.name(), doc.version(), nodes, wires, doc.seed(), doc.provenance(),
				doc.threadId(), doc.confirmHistory());
	}

	private static Node node(FlowDocument doc, String nodeId) {
		return doc.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst().orElse(null);
	}
}
