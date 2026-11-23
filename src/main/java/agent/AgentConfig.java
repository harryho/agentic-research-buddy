package agent;

import java.util.List;

/**
 * Provider selection is configuration, not code: budget-model chat endpoints behind one
 * record. The fake provider is the offline config; the OpenAI-compatible adapter is the
 * real one. Same loop, same prompts, either way.
 */
public record AgentConfig(String provider, String baseUrl, String model, String apiKeyEnvVar) {

	public static AgentConfig offlineFake(String model) {
		return new AgentConfig("fake", "offline://fake", model, "");
	}

	/** The no-endpoint config the studio ships with: every turn gets the same honest reply. */
	public static AgentConfig offlineDemo(String model) {
		return new AgentConfig("demo", "offline://demo", model, "");
	}

	/** Builds the provider for this config; testScript backs the fake, real config goes over HTTP. */
	public Provider provider(List<Provider.Response> testScript) {
		if ("fake".equals(provider)) {
			return new FakeProvider(testScript);
		}
		if ("demo".equals(provider)) {
			String reply = "no model endpoint configured - running offline: describe the change and "
					+ "configure a provider to stage cards; studio controls still work";
			return request -> Provider.Response.text(reply);
		}
		String key = System.getenv(apiKeyEnvVar);
		return new OpenAiCompatProvider(baseUrl, key == null ? "" : key);
	}
}
