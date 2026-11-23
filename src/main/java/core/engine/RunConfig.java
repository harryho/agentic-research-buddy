package core.engine;

import java.nio.file.Path;

/** Per-run configuration: the determinism seed and the directory the run writes into. */
public final class RunConfig {

	private final long seed;

	private final Path workDir;

	private RunConfig(long seed, Path workDir) {
		this.seed = seed;
		this.workDir = workDir;
	}

	public static RunConfig seeded(long seed) {
		return new RunConfig(seed, Path.of("."));
	}

	public RunConfig workDir(Path workDir) {
		return new RunConfig(seed, workDir);
	}

	public long seed() {
		return seed;
	}

	public Path workDir() {
		return workDir;
	}
}
