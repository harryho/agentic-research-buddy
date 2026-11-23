package agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Provider selection is configuration. The offline fake is scripted and deterministic
 * (exhaustion throws - the test harness's honesty); the demo provider answers every turn
 * with the same honest canned text, so the studio's smoke path never exhausts a script and
 * never fakes a staged proposal.
 */
class AgentConfigTest {

	@Test
	void fakeScriptIsDeterministicAndExhaustsLoudly() {
		AgentConfig config = AgentConfig.offlineFake("budget-model");
		FakeProvider provider = (FakeProvider) config.provider(List.of(Provider.Response.text("one")));
		assertEquals("one", provider.complete(new Provider.Request("m", List.of(), List.of())).text());
		try {
			provider.complete(new Provider.Request("m", List.of(), List.of()));
			assertTrue(false, "an exhausted script must throw, not stall");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("exhausted"));
		}
	}

	@Test
	void demoProviderAnswersEveryTurnWithoutExhausting() {
		AgentConfig config = AgentConfig.offlineDemo("budget-model");
		Provider provider = config.provider(List.of());
		for (int turn = 0; turn < 3; turn++) {
			Provider.Response response = provider.complete(new Provider.Request("budget-model",
					List.of(Provider.Turn.of("user", "hello " + turn)), List.of()));
			assertTrue(response.text().contains("model endpoint"), "the canned reply is honest");
			assertTrue(response.toolCalls().isEmpty(), "the demo never stages anything");
		}
	}
}
