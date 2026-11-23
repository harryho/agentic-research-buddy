package core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.engine.RunState;
import core.model.Node;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.tablesaw.api.Table;

/**
 * Golden tests for the registry's eleven types: typed descriptors (palette + grounding
 * digest source), seed-pure executors, exact metrics on shipped fixtures.
 */
class NodeExecutorTest {

	@TempDir
	Path workDir;

	private static final List<String> ELEVEN = List.of(
			"import-csv", "clean-drop-missing", "ols-regression", "random-forest", "knn-classify",
			"svm-classify", "eval-regression", "eval-classification", "residuals-plot", "prediction-table",
			"scatter-plot");

	private Object run(String type, Node node, Map<String, Object> inputs) throws Exception {
		NodeExecutor executor = NodeRegistry.standard().get(type).executor();
		return executor.execute(node, new NodeContext(7L, workDir, 0, inputs));
	}

	private Table fixture(String name) throws Exception {
		Path csv = workDir.resolve(name);
		if (!Files.exists(csv)) {
			Files.copy(Path.of("src/test/resources/fixtures").resolve(name), csv);
		}
		return Table.read().csv(csv.toFile());
	}

	@Test
	void allElevenTypesShipAsTypedDescriptors() {
		NodeRegistry registry = NodeRegistry.standard();
		assertEquals(ELEVEN.size(), registry.descriptors().size(), "registry v1 ships exactly the eleven");
		assertTrue(registry.descriptors().stream().map(NodeDescriptor::type).toList().containsAll(ELEVEN));
		for (NodeDescriptor d : registry.descriptors()) {
			assertTrue(d.purpose() != null && !d.purpose().isBlank(), d.type() + " states its purpose");
			for (ParamSpec p : d.params()) {
				assertTrue(p.type().equals("string") || p.type().equals("number") || p.type().equals("boolean"),
						d.type() + " param " + p.name() + " is typed");
			}
		}
		NodeDescriptor ols = registry.get("ols-regression").descriptor();
		assertTrue(ols.params().stream().anyMatch(p -> p.name().equals("target") && p.required()));
		assertTrue(registry.get("random-forest").descriptor().seedAware(), "model family is seed-aware");
		assertFalse(registry.get("knn-classify").descriptor().seedAware(),
				"kNN is deterministic: it never reads the run seed");
		assertFalse(registry.get("svm-classify").descriptor().seedAware(),
				"SVM is deterministic: it never reads the run seed");
		assertEquals(0, registry.get("import-csv").descriptor().inputs(), "import is a root");
	}

	@Test
	void olsRecoversTheKnownCoefficientsOnTheFixture() throws Exception {
		Table data = fixture("gradrate.csv");
		Table summary = (Table) run("ols-regression",
				new Node("m", "ols-regression", Map.of("target", "gradrate", "features", "hours,attendance")),
				Map.of("data", data));
		double hours = summary.where(summary.stringColumn("term").isEqualTo("hours")).numberColumn("coefficient")
				.getDouble(0);
		double attendance = summary.where(summary.stringColumn("term").isEqualTo("attendance"))
				.numberColumn("coefficient").getDouble(0);
		assertEquals(3.0, hours, 1e-6, "the surface is exactly 40 + 3h + 2a");
		assertEquals(2.0, attendance, 1e-6);
	}

	@Test
	void knnAndSvmClassifyTheirFixturesAboveThreshold() throws Exception {
		Table iris = fixture("iris45.csv");
		Table knnOut = (Table) run("knn-classify",
				new Node("m", "knn-classify",
						Map.of("target", "species", "features", "sepal_len,sepal_wid,petal_len,petal_wid", "k", 3)),
				Map.of("data", iris));
		assertTrue(accuracy(knnOut, "species", "predicted") >= 0.9, "kNN separates the iris subset");

		Table colon = fixture("colon40.csv");
		Table svmOut = (Table) run("svm-classify",
				new Node("m", "svm-classify",
						Map.of("target", "label", "features", "gene_a,gene_b,gene_c", "c", 1.0)),
				Map.of("data", colon));
		assertTrue(accuracy(svmOut, "label", "predicted") >= 0.8, "SVM separates the colon fixture");
	}

	@Test
	void forestIsSeedPureSameSeedIdenticalDifferentSeedDiffers() throws Exception {
		Table data = fixture("gradrate.csv");
		Map<String, Object> params = Map.of("target", "gradrate", "features", "hours,attendance", "trees", 12);
		Table a = (Table) run("random-forest", new Node("m", "random-forest", params), Map.of("data", data));
		Table b = (Table) run("random-forest", new Node("m", "random-forest", params), Map.of("data", data));
		assertEquals(a.rowCount(), b.rowCount());
		for (int i = 0; i < a.rowCount(); i++) {
			assertEquals(a.numberColumn("predicted").getDouble(i), b.numberColumn("predicted").getDouble(i), 1e-12,
					"same seed, same forest, row " + i);
		}
		Table otherSeed = (Table) run("random-forest",
				new Node("m2", "random-forest", params), Map.of("data", data),
				(21L));
		assertNotEquals(a.numberColumn("predicted").getDouble(0), otherSeed.numberColumn("predicted").getDouble(0),
				"a different seed moves the ensemble");
	}

	private Object run(String type, Node node, Map<String, Object> inputs, long seed) throws Exception {
		NodeExecutor executor = NodeRegistry.standard().get(type).executor();
		return executor.execute(node, new NodeContext(seed, workDir, 0, inputs));
	}

	@Test
	void evalNodesProduceExactMetrics() throws Exception {
		Table scored = Table.create()
				.addColumns(tech.tablesaw.api.DoubleColumn.create("actual", new double[] { 1, 2, 3, 4 }))
				.addColumns(tech.tablesaw.api.DoubleColumn.create("predicted", new double[] { 1, 2, 3, 6 }));
		Table reg = (Table) run("eval-regression",
				new Node("e", "eval-regression", Map.of("actual", "actual", "predicted", "predicted")),
				Map.of("scored", scored));
		assertEquals(3, reg.rowCount(), "r2, rmse, mae");
		assertEquals(1.0, metric(reg, "rmse"), 1e-9, "rmse of residuals (0,0,0,2)");
		assertEquals(0.5, metric(reg, "mae"), 1e-9, "mae of residuals (0,0,0,2)");

		Table cls = Table.create()
				.addColumns(tech.tablesaw.api.StringColumn.create("actual", new String[] { "a", "a", "b", "b" }))
				.addColumns(tech.tablesaw.api.StringColumn.create("predicted", new String[] { "a", "b", "b", "b" }));
		Table clsOut = (Table) run("eval-classification",
				new Node("e2", "eval-classification", Map.of("actual", "actual", "predicted", "predicted")),
				Map.of("scored", cls));
		assertEquals(0.75, metric(clsOut, "accuracy"), 1e-9, "3 of 4 correct");
	}

	private double metric(Table metrics, String name) {
		return metrics.where(metrics.stringColumn("metric").isEqualTo(name)).numberColumn("value").getDouble(0);
	}

	private double accuracy(Table scored, String actualCol, String predictedCol) {
		int hits = 0;
		for (int i = 0; i < scored.rowCount(); i++) {
			if (scored.getString(i, actualCol).equals(scored.getString(i, predictedCol))) {
				hits++;
			}
		}
		return (double) hits / scored.rowCount();
	}

	@Test
	void plotsAndTablesProduceNonEmptyArtifacts() throws Exception {
		Table data = fixture("gradrate.csv");
		Table scored = data.copy();
		scored.addColumns(tech.tablesaw.api.DoubleColumn.create("predicted",
				data.numberColumn("gradrate").asDoubleArray()));

		run("residuals-plot",
				new Node("p1", "residuals-plot", Map.of("actual", "gradrate", "predicted", "predicted", "out",
						"residuals.png")),
				Map.of("data", scored));
		run("scatter-plot",
				new Node("p2", "scatter-plot", Map.of("x", "hours", "y", "gradrate", "out", "scatter.png")),
				Map.of("data", scored));
		run("prediction-table", new Node("p3", "prediction-table", Map.of("out", "predictions.csv")),
				Map.of("data", scored));

		assertTrue(Files.size(workDir.resolve("residuals.png")) > 1000, "residual plot is a real PNG");
		assertTrue(Files.size(workDir.resolve("scatter.png")) > 1000, "scatter plot is a real PNG");
		assertTrue(Files.size(workDir.resolve("predictions.csv")) > 20, "prediction table artifact written");
	}

	@Test
	void cleanDropMissingDropsOrFills() throws Exception {
		Path csv = workDir.resolve("messy.csv");
		Files.writeString(csv, "a,b\n1,2\n,4\n5,\n7,8\n");
		Table messy = Table.read().csv(csv.toFile());
		Table dropped = (Table) run("clean-drop-missing",
				new Node("c1", "clean-drop-missing", Map.of("mode", "drop")), Map.of("data", messy));
		assertEquals(2, dropped.rowCount(), "rows with any missing value drop");

		Table filled = (Table) run("clean-drop-missing",
				new Node("c2", "clean-drop-missing", Map.of("mode", "fill", "value", 0)), Map.of("data", messy));
		assertEquals(4, filled.rowCount(), "fill keeps all rows");
		assertEquals(0.0, filled.numberColumn("a").getDouble(1), 1e-9, "missing a filled with the param value");
	}
}
