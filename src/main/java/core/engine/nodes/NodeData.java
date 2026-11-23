package core.engine.nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import tech.tablesaw.api.Table;

/** Conversions between the engine's Tablesaw tables and the numeric shapes trainers need. */
final class NodeData {

	record Coded(int[] y, List<String> levels) {
	}

	private NodeData() {
	}

	static List<String> features(String csv) {
		List<String> names = new ArrayList<>();
		for (String name : csv.split(",")) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	static double[][] matrix(Table table, List<String> columns) {
		double[][] x = new double[table.rowCount()][columns.size()];
		for (int r = 0; r < table.rowCount(); r++) {
			for (int c = 0; c < columns.size(); c++) {
				x[r][c] = table.numberColumn(columns.get(c)).getDouble(r);
			}
		}
		return x;
	}

	static double[] vector(Table table, String column) {
		return table.numberColumn(column).asDoubleArray();
	}

	/** Integer-codes a label column deterministically (levels sorted). */
	static Coded labels(Table table, String column) {
		TreeSet<String> distinct = new TreeSet<>();
		for (int r = 0; r < table.rowCount(); r++) {
			distinct.add(table.getString(r, column));
		}
		List<String> levels = List.copyOf(distinct);
		int[] y = new int[table.rowCount()];
		for (int r = 0; r < table.rowCount(); r++) {
			y[r] = levels.indexOf(table.getString(r, column));
		}
		return new Coded(y, levels);
	}

	static Table withPredicted(Table table, int[] predicted, List<String> levels) {
		Table out = table.copy();
		tech.tablesaw.api.StringColumn column = tech.tablesaw.api.StringColumn.create("predicted");
		for (int code : predicted) {
			column.append(levels.get(code));
		}
		out.addColumns(column);
		return out;
	}
}
