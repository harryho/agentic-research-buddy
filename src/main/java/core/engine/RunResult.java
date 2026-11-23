package core.engine;

import java.util.Map;

/** Outcome of one engine run: the terminal state plus each node's per-node status. */
public record RunResult(RunState state, Map<String, String> nodeStatuses) {

	public String nodeStatus(String nodeId) {
		return nodeStatuses.get(nodeId);
	}
}
