package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.Table;

public final class CleanDropMissingNode {

	private CleanDropMissingNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("clean-drop-missing", "transform",
				"drop rows with missing values or fill numeric gaps with a constant",
				List.of(new ParamSpec("mode", "string", "drop|fill", false),
						new ParamSpec("columns", "string", "comma list; default all", false),
						new ParamSpec("value", "number", "fill value for mode=fill", false)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Table table = cleanInput(node, ctx);
			String mode = String.valueOf(node.params().getOrDefault("mode", "drop"));
			List<String> columns = node.params().containsKey("columns")
					? NodeData.features(String.valueOf(node.params().get("columns")))
					: List.copyOf(table.columnNames());
			if ("fill".equals(mode)) {
				double fill = Double.parseDouble(String.valueOf(node.params().getOrDefault("value", "0")));
				Table out = table.copy();
				for (String column : columns) {
					if (out.column(column) instanceof tech.tablesaw.api.NumericColumn<?> numeric) {
						tech.tablesaw.api.DoubleColumn filled = numeric.asDoubleColumn();
						for (int r = 0; r < filled.size(); r++) {
							if (filled.isMissing(r)) {
								filled.set(r, fill);
							}
						}
						out.replaceColumn(column, filled);
					}
				}
				return out;
			}
			tech.tablesaw.selection.Selection keep = tech.tablesaw.selection.Selection.with();
			for (int r = 0; r < table.rowCount(); r++) {
				boolean complete = true;
				for (String column : columns) {
					if (table.column(column).isMissing(r)) {
						complete = false;
						break;
					}
				}
				if (complete) {
					keep.add(r);
				}
			}
			return table.where(keep);
		};
	}

	private static Table cleanInput(Node node, NodeContext ctx) {
		Object upstream = ctx.soleInput();
		if (!(upstream instanceof Table table)) {
			throw new IllegalStateException("clean-drop-missing node '" + node.id() + "' needs a table upstream");
		}
		return table;
	}
}
