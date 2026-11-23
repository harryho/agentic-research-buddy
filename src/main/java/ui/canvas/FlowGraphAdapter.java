package ui.canvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;

/**
 * The canvas's headless core: it projects the gate's document into a graph model and routes
 * every edit through the same document API the agent uses. The canvas never owns state -
 * moves go to the layout store (presentation); re-wire, re-parameterize, and delete build a
 * new document and hand it to the engine's edit path (semantic).
 */
public class FlowGraphAdapter {

	private final ui.StudioActions actions;

	private final LayoutStore layout;

	public FlowGraphAdapter(ui.StudioActions actions, LayoutStore layout) {
		this.actions = actions;
		this.layout = layout;
	}

	public Graph<NodeCell, String> graph() {
		Graph<NodeCell, String> graph = new DefaultDirectedGraph<>(String.class);
		for (core.model.Node node : actions.document().nodes()) {
			graph.addVertex(new NodeCell(node.id(), node.type()));
		}
		for (core.model.Wire wire : actions.document().wires()) {
			core.model.Node from = node(wire.from());
			core.model.Node to = node(wire.to());
			if (from != null && to != null) {
				graph.addEdge(new NodeCell(from.id(), from.type()), new NodeCell(to.id(), to.type()),
						wire.from() + "->" + wire.to());
			}
		}
		return graph;
	}

	public Map<String, LayoutStore.Pos> positions() {
		return layout.positions(actions.document().name());
	}

	/** The gate's live document - the canvas's single source of truth. */
	public core.model.FlowDocument document() {
		return actions.document();
	}

	/** The node's live run status for canvas badges. */
	public String statusOf(String nodeId) {
		return actions.engine().nodeStatus(nodeId);
	}

	/** A move is presentation only: layout side-file, document untouched, gate untouched. */
	public void moveNode(String nodeId, double x, double y) {
		layout.move(actions.document().name(), nodeId, x, y);
	}

	public void setParameter(String nodeId, String param, Object value) {
		actions.applyEdit(core.model.FlowEdits.setParameter(actions.document(), nodeId, param, value));
	}

	public void rewire(String fromId, String toId) {
		actions.applyEdit(core.model.FlowEdits.rewire(actions.document(), fromId, toId));
	}

	public void deleteNode(String nodeId) {
		actions.applyEdit(core.model.FlowEdits.deleteNode(actions.document(), nodeId));
	}

	private core.model.Node node(String nodeId) {
		return actions.document().nodes().stream()
				.filter(n -> n.id().equals(nodeId))
				.findFirst()
				.orElse(null);
	}
}
