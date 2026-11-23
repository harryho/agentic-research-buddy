package ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JPanel;
import ui.canvas.FlowGraphAdapter;
import ui.canvas.LayoutStore;
import ui.canvas.NodeCell;
import ui.canvas.PropertyEditor;

/**
 * The canvas region: the JGraphT projection of the gate's document, painted with Java2D
 * (nodes at their layout positions, wires as edges). Clicks select nodes for the property
 * editor; error clicks highlight the failing node (next task's seam).
 */
public class CanvasPanel extends JPanel {

	private FlowGraphAdapter adapter;

	private final PropertyEditor propertyEditor = new PropertyEditor();

	private String selectedNodeId;

	private String errorNodeId;

	private java.util.function.Consumer<String> selectHandler = id -> {
	};

	public CanvasPanel() {
		setLayout(new java.awt.BorderLayout());
		add(propertyEditor, java.awt.BorderLayout.SOUTH);
		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				NodeCell hit = hitTest(e.getX(), e.getY());
				if (hit != null) {
					selectedNodeId = hit.id();
					propertyEditor.show(node(hit.id()));
					selectHandler.accept(hit.id());
				} else {
					selectedNodeId = null;
					propertyEditor.show(null);
				}
				repaint();
			}
		});
	}

	/** Binds the canvas to an adapter (engine document + layout) and repaints. */
	public void bind(FlowGraphAdapter adapter) {
		this.adapter = adapter;
		refresh();
	}

	/** Re-projects the document; called after any edit or run-state change. */
	public void refresh() {
		if (adapter != null && node(selectedNodeId) == null) {
			selectedNodeId = null;
			propertyEditor.show(null);
		}
		if (adapter != null && errorNodeId != null && "green".equals(adapter.statusOf(errorNodeId))) {
			errorNodeId = null;
		}
		repaint();
	}

	public PropertyEditor propertyEditor() {
		return propertyEditor;
	}

	public void onSelect(java.util.function.Consumer<String> handler) {
		this.selectHandler = handler;
	}

	/** Highlights a failing node (from an error click or error event); null clears. */
	public void highlightError(String nodeId) {
		this.errorNodeId = nodeId;
		repaint();
	}

	public String selectedNodeId() {
		return selectedNodeId;
	}

	/** An error click selects AND highlights the failing node. */
	public String errorClick(String nodeId, String errorText) {
		this.selectedNodeId = nodeId;
		highlightError(nodeId);
		return nodeId;
	}

	public String errorNodeId() {
		return errorNodeId;
	}

	private core.model.Node node(String nodeId) {
		if (adapter == null || nodeId == null) {
			return null;
		}
		return adapter.document().nodes().stream()
				.filter(n -> n.id().equals(nodeId))
				.findFirst()
				.orElse(null);
	}

	private NodeCell hitTest(int x, int y) {
		if (adapter == null) {
			return null;
		}
		Map<String, LayoutStore.Pos> points = layoutPoints();
		for (NodeCell cell : adapter.graph().vertexSet()) {
			LayoutStore.Pos p = points.get(cell.id());
			if (p != null && x >= p.x() && x <= p.x() + NODE_W && y >= p.y() && y <= p.y() + NODE_H) {
				return cell;
			}
		}
		return null;
	}

	private static final int NODE_W = 150;

	private static final int NODE_H = 50;

	private Map<String, LayoutStore.Pos> layoutPoints() {
		Map<String, LayoutStore.Pos> points = new LinkedHashMap<>();
		if (adapter == null) {
			return points;
		}
		Map<String, LayoutStore.Pos> saved = adapter.positions();
		int i = 0;
		for (NodeCell cell : adapter.graph().vertexSet()) {
			LayoutStore.Pos sp = saved.get(cell.id());
			if (sp != null) {
				points.put(cell.id(), sp);
			} else {
				points.put(cell.id(), new LayoutStore.Pos(40 + (i % 3) * (NODE_W + 40),
						40 + (i / 3) * (NODE_H + 50)));
			}
			i++;
		}
		return points;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D gfx = (Graphics2D) g;
		if (adapter == null) {
			return;
		}
		Map<String, LayoutStore.Pos> points = layoutPoints();
		for (String edge : adapter.graph().edgeSet()) {
			NodeCell from = adapter.graph().getEdgeSource(edge);
			NodeCell to = adapter.graph().getEdgeTarget(edge);
			LayoutStore.Pos a = points.get(from.id());
			LayoutStore.Pos b = points.get(to.id());
			if (a != null && b != null) {
				gfx.drawLine((int) (a.x() + NODE_W / 2), (int) (a.y() + NODE_H / 2),
						(int) (b.x() + NODE_W / 2), (int) (b.y() + NODE_H / 2));
			}
		}
		for (Map.Entry<String, LayoutStore.Pos> e : points.entrySet()) {
			NodeCell cell = adapter.graph().vertexSet().stream()
					.filter(v -> v.id().equals(e.getKey()))
					.findFirst()
					.orElse(null);
			if (cell == null) {
				continue;
			}
			String status = adapter.statusOf(e.getKey());
			if (e.getKey().equals(errorNodeId)) {
				gfx.setPaint(new java.awt.Color(200, 60, 60));
			} else if (e.getKey().equals(selectedNodeId)) {
				gfx.setPaint(new java.awt.Color(60, 100, 200));
			} else {
				gfx.setPaint(new java.awt.Color(70, 130, 100));
			}
			gfx.fillRoundRect((int) e.getValue().x(), (int) e.getValue().y(), NODE_W, NODE_H, 12, 12);
			gfx.setPaint(java.awt.Color.WHITE);
			String[] lines = cell.label().split("\n");
			gfx.drawString(lines[0], (int) e.getValue().x() + 10, (int) e.getValue().y() + 20);
			gfx.drawString(lines.length > 1 ? lines[1] : "", (int) e.getValue().x() + 10,
					(int) e.getValue().y() + 38);
			gfx.drawString(status, (int) e.getValue().x() + 10, (int) e.getValue().y() + NODE_H - 4);
		}
	}

}
