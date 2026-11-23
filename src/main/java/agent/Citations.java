package agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Citability: every proposed field must trace to a source the agent actually saw this
 * turn. A proposal with no citations, or citing sources outside the assembled grounding,
 * is unverifiable and refused with the offending names.
 */
public final class Citations {

	private Citations() {
	}

	public static List<String> verify(Map<String, String> citations, Set<String> seenSourceIds) {
		List<String> refusals = new ArrayList<>();
		if (citations == null || citations.isEmpty()) {
			refusals.add("proposal carries no citations - every field must trace to the grounding");
			return refusals;
		}
		for (Map.Entry<String, String> citation : citations.entrySet()) {
			if (!seenSourceIds.contains(citation.getValue())) {
				refusals.add("field '" + citation.getKey() + "' cites unseen source '" + citation.getValue()
						+ "'");
			}
		}
		return refusals;
	}
}
