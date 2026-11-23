package agent;

import core.engine.FlowEngine;
import java.util.Set;

/**
 * One source of citable digest ids for the open flow's registry - shared by the tests,
 * the loop's grounding, and the tool's citation check.
 */
public final class FlowGraphIds {

	private FlowGraphIds() {
	}

	public static Set<String> of(FlowEngine engine) {
		return java.util.Collections.unmodifiableSet(
				java.util.Set.copyOf(Grounding.digestSources(engine).stream()
						.map(Grounding.Source::id)
						.collect(java.util.stream.Collectors.toSet())));
	}
}
