package core.engine;

import core.model.FlowDocument;
import core.model.FlowVersion;
import core.model.Node;
import core.model.Wire;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tech.tablesaw.api.Table;
import tech.tablesaw.selection.Selection;

/**
 * The headless engine. Every build/execute entry point is a gated method on the confirm
 * gate — there is no ungated path to a run. The scheduler turns wires into a deterministic
 * topological execution order, run on a virtual thread with run-all / run-to-node / stop
 * controls. Events stream into a RunLog (append-only JSONL archive + bounded dock buffer).
 * An edit landing mid-run cancels the run at the next node boundary.
 */
public class FlowEngine {

	private final RunConfig config;

	private final ConfirmGate gate = new ConfirmGate();

	private final core.registry.NodeRegistry registry;

	private final Map<String, String> liveStatuses = new ConcurrentHashMap<>();

	private volatile boolean stopRequested;

	private volatile RunLog lastRunLog;

	private final java.util.concurrent.atomic.AtomicLong runCounter = new java.util.concurrent.atomic.AtomicLong();

	private final java.util.List<java.util.function.Consumer<RunEvent>> runListeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Subscribes to every run's event stream; each new run log inherits the subscription. */
	public void addRunListener(java.util.function.Consumer<RunEvent> listener) {
		runListeners.add(listener);
	}

	public FlowEngine(RunConfig config) {
		this(config, core.registry.NodeRegistry.standard());
	}

	/** A custom registry replaces the standard set wholesale (registry extension proof). */
	public FlowEngine(RunConfig config, core.registry.NodeRegistry registry) {
		gate.setBuildValidator(doc -> ColumnShapes.violations(doc, registry, config.workDir()));
		this.config = config;
		this.registry = registry;
	}

	/**
	 * Ad-hoc executor registration for tests and demos — the typed route is
	 * registry.register(descriptor, executor).
	 */
	public synchronized void registerExecutor(String type, NodeExecutor executor) {
		core.registry.NodeDescriptor adhoc = new core.registry.NodeDescriptor(type, "adhoc", "ad-hoc executor",
				java.util.List.of(), 1, 1, false);
		registry.register(adhoc, executor);
	}

	public void propose(FlowDocument draft, String cardId, String actor) {
		gate.propose(draft, cardId, actor);
	}

	public ConfirmEvent userConfirmBuild(String cardId, String actor) {
		return gate.userConfirmBuild(cardId, actor);
	}

	public void discardProposal(String actor) {
		gate.discardProposal(actor);
	}

	public FlowVersion materialize(String actor) {
		return gate.materialize(actor);
	}

	public ConfirmEvent userConfirmRun(String actor) {
		return gate.userConfirmRun(actor);
	}

	public void applyEdit(FlowDocument edited, String actor) {
		gate.applyEdit(edited, actor);
	}

	/** Adopts a freshly loaded flow document; the gate state derives from its history. */
	public void restore(FlowDocument persisted) {
		gate.restore(persisted);
	}

	public RunHandle startRun(String actor) {
		return gatedStart(actor, null);
	}

	public RunHandle runTo(String nodeId, String actor) {
		return gatedStart(actor, nodeId);
	}

	public void stop(String actor) {
		if (gate.state() == GateState.RUNNING) {
			stopRequested = true;
		}
	}

	private RunHandle gatedStart(String actor, String runToTarget) {
		gate.startRun(actor);
		FlowDocument runDoc = gate.document();
		for (Node node : runDoc.nodes()) {
			liveStatuses.put(node.id(), "idle");
		}
		stopRequested = false;
		long runId = runCounter.incrementAndGet();
		RunLog log = new RunLog(config.workDir().resolve("runs").resolve("run-" + runId + ".jsonl"));
		runListeners.forEach(log::addListener);
		lastRunLog = log;
		ExecutorService virtual = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
		CompletableFuture<RunResult> future = CompletableFuture
				.supplyAsync(() -> runDocument(runDoc, runToTarget, log), virtual);
		future.whenComplete((r, t) -> virtual.shutdown());
		return new RunHandle(future, runId);
	}

	/** The live registry - the tool surface reads the digest from the same source the engine executes. */
	public core.registry.NodeRegistry registryView() {
		return registry;
	}

	public GateState gateState() {
		return gate.state();
	}

	public FlowDocument document() {
		return gate.document();
	}

	public List<GateEvent> gateEvents() {
		return gate.events();
	}

	public String nodeStatus(String nodeId) {
		return liveStatuses.getOrDefault(nodeId, "unknown");
	}

	public List<RunEvent> runLogReplay() {
		return lastRunLog == null ? List.of() : lastRunLog.replay();
	}

	public List<RunEvent> runLogDock() {
		return lastRunLog == null ? List.of() : lastRunLog.dockSnapshot();
	}

	/** Replays an addressed run's archive; each run keeps its own file under runs/. */
	public List<RunEvent> runArchive(long runId) {
		return RunLog.replay(config.workDir().resolve("runs").resolve("run-" + runId + ".jsonl"));
	}

	private RunResult runDocument(FlowDocument doc, String runToTarget, RunLog log) {
		Map<String, String> statuses = new LinkedHashMap<>();
		for (Node node : doc.nodes()) {
			statuses.put(node.id(), "idle");
		}
		List<Node> order = runToTarget == null ? Scheduler.executionOrder(doc)
				: Scheduler.orderUpTo(doc, runToTarget);
		Map<String, Object> nodeOutputs = new HashMap<>();
		RunState terminal = runToTarget == null ? RunState.COMPLETED : RunState.STOPPED;
		log.append("info", "", "run-started", null);
		int index = 0;
		for (Node node : order) {
			if (gate.takeSupersession() != null) {
				log.append("warn", "", "run-invalidated", "superseded");
				terminal = RunState.STOPPED;
				break;
			}
			if (stopRequested) {
				terminal = RunState.STOPPED;
				break;
			}
			statuses.put(node.id(), "running");
			liveStatuses.put(node.id(), "running");
			log.append("info", node.id(), "node-started", null);
			Map<String, Object> inputs = new LinkedHashMap<>();
			for (Wire wire : doc.wires()) {
				if (wire.to().equals(node.id())) {
					inputs.put(wire.from(), nodeOutputs.get(wire.from()));
				}
			}
			NodeContext ctx = new NodeContext(config.seed(), config.workDir(), index, inputs);
			Object output;
			try {
				output = registry.get(node.type()).executor().execute(node, ctx);
			} catch (Exception e) {
				statuses.put(node.id(), "error");
				liveStatuses.put(node.id(), "error");
				log.append("error", node.id(), "node-error", e.getMessage());
				terminal = RunState.FAILED_RUN;
				break;
			}
			nodeOutputs.put(node.id(), output);
			statuses.put(node.id(), "green");
			liveStatuses.put(node.id(), "green");
			log.append("info", node.id(), "node-green", null);
			index++;
		}
		String reason = terminal == RunState.COMPLETED ? "ok"
				: terminal == RunState.FAILED_RUN ? "node failed"
						: stopRequested ? "stop requested"
								: runToTarget != null ? "target reached" : "superseded";
		log.append("info", "",
				terminal == RunState.COMPLETED ? "run-completed"
						: terminal == RunState.FAILED_RUN ? "run-failed" : "run-stopped",
				reason);
		log.close();
		gate.runFinished(terminal);
		return new RunResult(terminal, statuses);
	}


}
