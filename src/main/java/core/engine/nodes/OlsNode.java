package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

public final class OlsNode {

	private OlsNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("ols-regression", "model",
				"ordinary least squares linear regression with a coefficient summary",
				List.of(new ParamSpec("target", "string", "numeric target column", true),
						new ParamSpec("features", "string", "comma list of numeric columns", true)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("ols-regression node '" + node.id() + "' needs a table upstream");
			}
			String target = String.valueOf(node.params().get("target"));
			List<String> features = NodeData.features(String.valueOf(node.params().get("features")));
			double[] y = NodeData.vector(table, target);
			double[][] x = NodeData.matrix(table, features);
			OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
			ols.newSampleData(y, x);
			double[] beta = ols.estimateRegressionParameters();
			StringColumn termColumn = StringColumn.create("term");
			tech.tablesaw.api.DoubleColumn coefficientColumn = tech.tablesaw.api.DoubleColumn.create("coefficient");
			String[] terms = new String[beta.length];
			terms[0] = "intercept";
			System.arraycopy(features.toArray(new String[0]), 0, terms, 1, features.size());
			for (int i = 0; i < beta.length; i++) {
				termColumn.append(terms[i]);
				coefficientColumn.append(beta[i]);
			}
			return Table.create(termColumn, coefficientColumn);
		};
	}
}
