package ui;

import core.engine.FlowEngine;
import core.engine.GateState;
import core.engine.RunConfig;
import core.engine.RunEvent;
import core.engine.RunHandle;
import core.model.FlowDocument;

/**
 * The shell's run controls as one headless-testable object: every user click maps to the
 * same engine primitive the agent uses, in the gated order, as the human actor.
 */
public class StudioActions {

	private final FlowEngine engine;

	public StudioActions(RunConfig config) {
		try {
			this.library = new core.persist.Library(config.workDir());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		this.engine = new FlowEngine(config);
	}

	/** Wires a dock to every run's event stream and seeds it from the last run's archive. */
	public void bind(LogDock.LogDockModel dock) {
		engine.addRunListener(dock::onEvent);
		dock.replay(engine.runLogReplay());
	}

	public void propose(FlowDocument draft, String cardId) {
		engine.propose(draft, cardId, "user");
	}

	public void confirmBuild(String cardId) {
		engine.userConfirmBuild(cardId, "user");
	}

	public void materialize() {
		engine.materialize("user");
	}

	public void confirmRun() {
		engine.userConfirmRun("user");
	}

	public RunHandle startRun() {
		return engine.startRun("user");
	}

	public void stop() {
		engine.stop("user");
	}

	public void applyEdit(FlowDocument edited) {
		engine.applyEdit(edited, "user");
	}

	public void discardProposal() {
		engine.discardProposal("user");
	}

	public GateState gateState() {
		return engine.gateState();
	}

	public FlowDocument document() {
		return engine.document();
	}

	public FlowEngine engine() {
		return engine;
	}

	private agent.AgentLoop agentLoop;

	private agent.AgentDrafts agentDrafts;

	private final core.persist.Library library;

	/** A demo opened from the library: copied into the working library, restored into the engine. */
	public core.model.FlowDocument openDemo(String name) throws java.io.IOException {
		core.model.FlowDocument doc = library.copyDemo(name, "active");
		engine.restore(doc);
		return engine.document();
	}

	/** The studio's agent half: the grounded loop and its staged drafts. */
	public void attachAgent(agent.AgentLoop loop, agent.AgentDrafts drafts) {
		this.agentLoop = loop;
		this.agentDrafts = drafts;
	}

	public boolean agentAvailable() {
		return agentLoop != null;
	}

	public agent.AgentLoop agentLoop() {
		return agentLoop;
	}

	public agent.AgentDrafts agentDrafts() {
		return agentDrafts;
	}

	/** One honest probe: a provider that answers is up; one that throws degrades the studio. */
	public boolean probeAgent() {
		if (agentLoop == null) {
			return false;
		}
		return agentLoop.run("studio availability probe").kind() != agent.AgentLoop.Kind.DEGRADED;
	}

	/** The human confirm of a staged card - the only write path the agent's work has. */
	public core.model.FlowDocument applyCard(String cardId) {
		if (agentDrafts == null) {
			throw new IllegalStateException("no agent attached");
		}
		return agentDrafts.apply(cardId);
	}
}
