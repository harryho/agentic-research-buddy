package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.Table;

public final class ImportCsvNode {

	private ImportCsvNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("import-csv", "import", "load a CSV dataset from the run work dir",
				List.of(new ParamSpec("path", "string", "csv file name", true)), 0, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object pathValue = node.params().get("path");
			if (pathValue == null) {
				throw new IllegalArgumentException("import-csv node '" + node.id() + "' is missing param: path");
			}
			Path csvPath = ctx.workDir().resolve(String.valueOf(pathValue));
			return Table.read().csv(csvPath.toFile());
		};
	}
}
