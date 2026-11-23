package core.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.GateState;
import core.engine.RunConfig;
import core.engine.RunState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three shipped archetypes run green end-to-end, headless, on the shipped data. */
class DemoArchetypeTest {

	@TempDir
	Path workDir;

	private void runDemo(String name, String dataFile, List<String> artifacts) throws Exception {
		Path runDir = workDir.resolve(name);
		Files.createDirectories(runDir);
		Files.copy(Path.of("src/main/resources/demo/data").resolve(dataFile), runDir.resolve(dataFile));

		Library library = new Library(workDir.resolve("workspace"));
		FlowStore.LoadedFlow loaded = library.openDemo(name);

		FlowEngine engine = new FlowEngine(RunConfig.seeded(42L).workDir(runDir));
		engine.propose(loaded.document(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state(), name + " runs green end-to-end");
		assertEquals(GateState.COMPLETED, engine.gateState());
		for (String artifact : artifacts) {
			assertTrue(Files.size(runDir.resolve(artifact)) > 20, name + " produced " + artifact);
		}
	}

	@Test
	void gradrateRegressionArchetypeRunsGreen() throws Exception {
		runDemo("gradrate-ols", "gradrate.csv", List.of("residuals.png", "predictions.csv", "gradrate-scatter.png"));
	}

	@Test
	void irisKnnArchetypeRunsGreen() throws Exception {
		runDemo("iris-knn", "iris.csv", List.of("knn-predictions.csv"));
	}

	@Test
	void colonSvmArchetypeRunsGreen() throws Exception {
		runDemo("colon-svm", "colon.csv", List.of("svm-predictions.csv"));
	}
}
