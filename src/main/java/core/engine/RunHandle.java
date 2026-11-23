package core.engine;

import java.util.concurrent.CompletableFuture;

/** A started run: await its terminal RunResult from any thread; runId addresses its archive. */
public record RunHandle(CompletableFuture<RunResult> future, long runId) {

	public RunResult await() {
		try {
			return future.get();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
