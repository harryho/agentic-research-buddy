package ui.canvas;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * The property editor for one canvas node. Values are parsed by shape (integer, decimal,
 * boolean, else string) to match the registry's typed parameter specs; applying routes the
 * whole edit through the adapter - the editor never touches the document directly.
 */
public class PropertyEditor extends JPanel {

	private final DefaultTableModel rows = new DefaultTableModel(new Object[] { "param", "value" }, 0) {
		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 1;
		}
	};

	private final JTable table = new JTable(rows);

	private final JButton apply = new JButton("apply");

	private String nodeId;

	public PropertyEditor() {
		setLayout(new java.awt.BorderLayout());
		setBorder(BorderFactory.createTitledBorder("node"));
		add(new JScrollPane(table), java.awt.BorderLayout.CENTER);
		add(apply, java.awt.BorderLayout.SOUTH);
	}

	/** Loads a node's parameters for editing; call when the canvas selection changes. */
	public void show(core.model.Node node) {
		this.nodeId = node == null ? null : node.id();
		rows.setRowCount(0);
		if (node != null) {
			setBorder(BorderFactory.createTitledBorder("node: " + node.id() + " (" + node.type() + ")"));
			Map<String, Object> sorted = new LinkedHashMap<>(new java.util.TreeMap<>(node.params()));
			for (Map.Entry<String, Object> e : sorted.entrySet()) {
				rows.addRow(new Object[] { e.getKey(), String.valueOf(e.getValue()) });
			}
		} else {
			setBorder(BorderFactory.createTitledBorder("node"));
		}
	}

	/** Applies every edited row through the adapter; returns false when nothing is loaded. */
	public boolean onApply(FlowGraphAdapter adapter) {
		if (nodeId == null) {
			return false;
		}
		for (int i = 0; i < rows.getRowCount(); i++) {
			String param = String.valueOf(rows.getValueAt(i, 0));
			String raw = String.valueOf(rows.getValueAt(i, 1));
			adapter.setParameter(nodeId, param, parse(raw));
		}
		return true;
	}

	static Object parse(String raw) {
		String trimmed = raw.trim();
		try {
			return Integer.valueOf(trimmed);
		} catch (NumberFormatException e) {
			// fall through
		}
		try {
			return Double.valueOf(trimmed);
		} catch (NumberFormatException e) {
			// fall through
		}
		if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
			return Boolean.valueOf(trimmed);
		}
		return raw;
	}

	public JButton applyButton() {
		return apply;
	}
}
