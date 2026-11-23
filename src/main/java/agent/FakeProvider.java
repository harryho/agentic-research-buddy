package agent;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic offline double: a scripted queue of responses the loop consumes in
 * order. No network, no randomness - the whole agent loop tests offline. Scripted runs
 * also record the requests they saw, so tests can assert what the loop sent.
 */
public class FakeProvider implements Provider {

	private final List<Response> script;

	private final List<Request> seen = new ArrayList<>();

	public FakeProvider(List<Response> script) {
		this.script = List.copyOf(script);
	}

	@Override
	public Response complete(Request request) {
		seen.add(request);
		if (seen.size() > script.size()) {
			throw new IllegalStateException("fake provider script exhausted after " + script.size() + " calls");
		}
		return script.get(seen.size() - 1);
	}

	public List<Request> seenRequests() {
		return List.copyOf(seen);
	}
}
