package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

/** Classification metrics: accuracy plus per-pair confusion counts. */
public final class EvalClassificationNode {

	private EvalClassificationNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("eval-classification", "evaluate", "accuracy and confusion counts of a scored table",
				List.of(new ParamSpec("actual", "string", "actual label column", true),
						new ParamSpec("predicted", "string", "predicted label column", true)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("eval-classification node '" + node.id() + "' needs a table upstream");
			}
			String actual = String.valueOf(node.params().get("actual"));
			String predicted = String.valueOf(node.params().get("predicted"));
			int hits = 0;
			TreeSet<String> pairs = new TreeSet<>();
			int total = table.rowCount();
			for (int r = 0; r < total; r++) {
				String a = table.getString(r, actual);
				String p = table.getString(r, predicted);
				if (a.equals(p)) {
					hits++;
				} else {
					pairs.add(a + ">" + p);
				}
			}
			StringColumn metricColumn = StringColumn.create("metric");
			tech.tablesaw.api.DoubleColumn valueColumn = tech.tablesaw.api.DoubleColumn.create("value");
			metricColumn.append("accuracy");
			valueColumn.append((double) hits / total);
			for (String pair : pairs) {
				int count = 0;
				for (int r = 0; r < total; r++) {
					String a = table.getString(r, actual);
					String p = table.getString(r, predicted);
					if (!a.equals(p) && (a + ">" + p).equals(pair)) {
						count++;
					}
				}
				metricColumn.append("confusion:" + pair);
				valueColumn.append(count);
			}
			return Table.create(metricColumn, valueColumn);
		};
	}
}
