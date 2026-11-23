package agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The agent loop: ground once per turn, call the provider with native tools, execute tool
 * calls against the registry, and stop at a staged card, a clarification question, or a
 * refusal - all typed outcomes. Provider failures degrade, never crash: the studio stays
 * usable and the outcome says so.
 */
public class AgentLoop {

	public enum Kind {
		PROPOSAL_STAGED, CLARIFICATION, DEGRADED
	}

	public record Outcome(Kind kind, String cardId, String message, int providerRounds) {
	}

	private final Provider provider;

	private final ToolRegistry registry;

	private final String model;

	private final Supplier<List<Grounding.Source>> groundingOrder;

	private final int groundingBudgetChars;

	public AgentLoop(Provider provider, ToolRegistry registry, String model,
			Supplier<List<Grounding.Source>> groundingOrder, int groundingBudgetChars) {
		this.provider = provider;
		this.registry = registry;
		this.model = model;
		this.groundingOrder = groundingOrder;
		this.groundingBudgetChars = groundingBudgetChars;
	}

	public Outcome run(String userMessage) {
		return run(userMessage, 6);
	}

	/** Read-only view of the tool specs - the adapter wire-format tests use the same source. */
	public List<Map<String, Object>> toolsForShapeTest() {
		return registry.schemas();
	}

	public Outcome run(String userMessage, int maxRounds) {
		Grounding.Assembled grounding = Grounding.assemble(groundingBudgetChars, groundingOrder.get());
		List<Provider.Turn> turns = new ArrayList<>();
		turns.add(Provider.Turn.of("system",
				"You compose data-analysis flows only from the cited grounding. "
						+ "Every proposed field must cite a source id from the grounding."));
		turns.add(Provider.Turn.of("user", userMessage));
		turns.add(Provider.Turn.of("system", grounding.text()));
		for (int round = 0; round < maxRounds; round++) {
			Provider.Response response;
			try {
				response = provider.complete(new Provider.Request(model, turns, registry.schemas()));
			} catch (Exception e) {
				return new Outcome(Kind.DEGRADED, null, "agent unreachable: " + e.getMessage(), round);
			}
			if (response.toolCalls().isEmpty()) {
				return new Outcome(Kind.CLARIFICATION, null, response.text(), round + 1);
			}
			for (Provider.ToolCall call : response.toolCalls()) {
				ToolRegistry.Tool tool = registry.get(call.name());
				ToolResult result = tool == null ? ToolResult.refused("unknown tool: " + call.name())
						: tool.execute(call.args());
				turns.add(new Provider.Turn("tool", call.name() + " -> " + (result.ok()
						? result.summary()
						: "refused: " + result.refusalReason()), List.of()));
				if (result.ok() && (call.name().equals("propose_flow") || call.name().equals("propose_diagnosis"))) {
					return new Outcome(Kind.PROPOSAL_STAGED, String.valueOf(result.data()), result.summary(),
							round + 1);
				}
			}
		}
		return new Outcome(Kind.CLARIFICATION, null,
				"tool budget exhausted; answer the pending question or adjust the request", maxRounds);
	}
}
