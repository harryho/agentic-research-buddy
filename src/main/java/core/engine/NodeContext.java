package core.engine;

import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

/**
 * What a node executor sees: the run's seed (all randomness must derive from it), the run's
 * work directory, the node's position in the document, and the node's inputs — one entry per
 * wire targeting the node, keyed by the upstream node id.
 */
public record NodeContext(long seed, Path workDir, int nodeIndex, Map<String, Object> inputs) {

	public Object input(String fromNodeId) {
		return inputs.get(fromNodeId);
	}

	public Object soleInput() {
		if (inputs.size() != 1) {
			throw new IllegalStateException(
					"expected exactly one upstream wire, found " + inputs.size());
		}
		return inputs.values().iterator().next();
	}

	/** THE derivation scheme: Random seeded with seed ^ (nodeIndex * golden-ratio constant). */
	public Random newRandom() {
		return new Random(seed ^ (nodeIndex * 0x9E3779B97F4A7C15L));
	}
}
