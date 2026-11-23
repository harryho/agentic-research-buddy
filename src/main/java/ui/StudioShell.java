package ui;

import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * The studio window: agent chat with proposal cards (left), canvas (center), log dock
 * (bottom), flow tree (right), and the gated run controls. The shell is assembly only -
 * every behavior lives in the panels' models, the agent loop, and the engine, which is why
 * the machine-checked surface stays headless-testable.
 */
public class StudioShell {

	private StudioShell() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length >= 2 && args[0].equals("smoke")) {
			runSmoke(Path.of(args[1]));
			return;
		}
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("headless environment: the studio shell needs a display");
			return;
		}
		Path workDir = Path.of(args.length > 0 ? args[0] : "workspace");
		SwingUtilities.invokeLater(() -> {
			try {
				build(workDir).setVisible(true);
			} catch (RuntimeException e) {
				System.out.println("shell failed to start: " + e.getMessage());
			}
		});
	}

	/**
	 * The packaged smoke pass: no display needed - the same headless engine surface the
	 * machine tests use. Creates a project, ships the demo into it, drives the gated path
	 * through a full run, and prints the artifacts it landed so the smoke script can
	 * assert them in open formats under the workspace root.
	 */
	static void runSmoke(Path workDir) throws Exception {
		core.persist.Library library = new core.persist.Library(workDir);
		library.createProject("smoke");
		core.model.FlowDocument doc = library.copyDemo("gradrate-ols", "smoke");
		Files.writeString(workDir.resolve("gradrate.csv"), "hours,gradrate\n2,50\n4,60\n7,80\n");
		core.engine.FlowEngine engine = new core.engine.FlowEngine(
				core.engine.RunConfig.seeded(42L).workDir(workDir));
		engine.restore(doc);
		engine.propose(engine.document(), "card-1", "smoke");
		engine.userConfirmBuild("card-1", "smoke");
		engine.materialize("smoke");
		engine.userConfirmRun("smoke");
		core.engine.RunHandle handle = engine.startRun("smoke");
		handle.future().get(120, java.util.concurrent.TimeUnit.SECONDS);
		Path versionZip = library.projectDir("smoke").resolve("v" + doc.version() + ".flow.zip");
		Path runLog = workDir.resolve("runs").resolve("run-" + handle.runId() + ".jsonl");
		System.out.println("[smoke] project created: " + library.projectDir("smoke"));
		System.out.println("[smoke] demo copied: v" + doc.version() + " thread attached");
		System.out.println("[smoke] run " + handle.runId() + " completed: "
				+ engine.runLogReplay().size() + " events");
		System.out.println("[smoke] version artifact: " + versionZip + " ("
				+ (Files.exists(versionZip) ? Files.size(versionZip) : -1) + " bytes)");
		System.out.println("[smoke] run log artifact: " + runLog + " ("
				+ (Files.exists(runLog) ? Files.size(runLog) : -1) + " bytes)");
		if (!Files.exists(versionZip) || !Files.exists(runLog)) {
			System.out.println("[smoke] FAIL: expected artifacts missing");
			System.exit(3);
		}
		System.out.println("[smoke] PASS");
	}

	static JFrame build(Path workDir) {
		com.formdev.flatlaf.FlatLightLaf.setup();
		StudioActions actions = new StudioActions(core.engine.RunConfig.seeded(42L).workDir(workDir));
		AgentChatPanel chat = new AgentChatPanel();
		CanvasPanel canvas = new CanvasPanel();
		LogDock dock = new LogDock();
		ConfirmCardPanel cards = new ConfirmCardPanel();
		core.persist.Library library;
		try {
			library = new core.persist.Library(workDir);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		javax.swing.JLabel gateLabel = new javax.swing.JLabel();
		Runnable refreshGate = () -> gateLabel.setText("gate: " + actions.gateState() + "   actor: user");
		refreshGate.run();
		LibraryPanel tree = new LibraryPanel(library, label -> {
			try {
				if (label.startsWith("demo:")) {
					actions.openDemo(label.substring("demo:".length()));
				} else if (label.startsWith("version:")) {
					String[] parts = label.split(":");
					actions.engine().restore(library.openVersion(parts[1], Integer.parseInt(parts[2])));
				}
				canvas.refresh();
			} catch (java.io.IOException e) {
				chat.model().addMessage("system", "open failed: " + e.getMessage());
			}
			refreshGate.run();
		});
		cards.onChange(refreshGate);
		actions.bind(dock.model());
		dock.onError(event -> {
			canvas.errorClick(event.nodeId(), event.message());
			chat.model().openDiagnosis(event.nodeId(), event.message());
		});
		canvas.bind(new ui.canvas.FlowGraphAdapter(actions, new ui.canvas.LayoutStore(workDir.resolve("layout"))));

		// agent wiring: grounded loop, staged cards apply through the gate, honest degradation
		agent.AgentConfig config = agent.AgentConfig.offlineDemo("budget-model");
		agent.AgentDrafts drafts = new agent.AgentDrafts(actions.engine());
		agent.SessionThread session = new agent.SessionThread("thread-studio");
		agent.ToolRegistry tools = new agent.ToolRegistry();
		agent.AgentTools.install(tools, actions.engine(), drafts, workDir, session::snapshot,
				() -> agent.FlowGraphIds.of(actions.engine()));
		agent.AgentLoop loop = new agent.AgentLoop(config.provider(List.of()), tools, config.model(),
				() -> agent.Grounding.assembleOrder(actions.engine(), session.snapshot(), workDir,
						List.of("data.csv"), actions.engine().runLogReplay()),
				4000);
		actions.attachAgent(loop, drafts);
		boolean agentUp = actions.probeAgent();
		chat.model().addMessage("system", agentUp
				? "agent connected"
				: "agent unavailable (offline) - studio controls still work; agent input disabled");
		chat.input().setEnabled(agentUp);
		cards.bind(drafts, actions.document());
		chat.input().addActionListener(e -> {
			String text = chat.input().getText().trim();
			if (text.isEmpty() || !actions.agentAvailable()) {
				return;
			}
			chat.input().setText("");
			session.add("user", text);
			chat.model().addMessage("user", text);
			new Thread(() -> {
				agent.AgentLoop.Outcome outcome = actions.agentLoop().run(text);
				SwingUtilities.invokeLater(() -> {
					if (outcome.kind() == agent.AgentLoop.Kind.PROPOSAL_STAGED) {
						chat.model().addMessage("agent", outcome.message() + " (card " + outcome.cardId() + ")");
					} else {
						chat.model().addMessage("agent", outcome.message());
					}
					cards.refresh();
					canvas.refresh();
				});
			}, "agent-loop").start();
		});

		// gated run controls: every button is one engine primitive in the gated order
		JPanel runControls = new JPanel(new java.awt.FlowLayout());
		JTextField cardField = new JTextField("card-1", 10);
		runControls.add(cardField);
		runControls.add(gateLabel);
		JButton confirmBuild = new JButton("confirm build");
		JButton materialize = new JButton("materialize");
		JButton confirmRun = new JButton("confirm run");
		JButton start = new JButton("start run");
		JButton stop = new JButton("stop");
		runControls.add(confirmBuild);
		runControls.add(materialize);
		runControls.add(confirmRun);
		runControls.add(start);
		runControls.add(stop);
		confirmBuild.addActionListener(e -> reportState(gateLabel, actions, () -> {
			actions.confirmBuild(cardField.getText().trim());
			canvas.refresh();
		}));
		materialize.addActionListener(e -> reportState(gateLabel, actions, () -> {
			actions.materialize();
			canvas.refresh();
		}));
		confirmRun.addActionListener(e -> reportState(gateLabel, actions, actions::confirmRun));
		start.addActionListener(e -> reportState(gateLabel, actions, () -> {
			actions.startRun().future().handle((result, error) -> {
				SwingUtilities.invokeLater(() -> {
					canvas.refresh();
					gateLabel.setText("gate: " + actions.gateState() + "   actor: user");
				});
				return null;
			});
		}));
		stop.addActionListener(e -> reportState(gateLabel, actions, actions::stop));

		JPanel chatColumn = new JPanel(new BorderLayout());
		chatColumn.add(chat, BorderLayout.CENTER);
		chatColumn.add(cards, BorderLayout.SOUTH);
		JPanel centerColumn = new JPanel(new BorderLayout());
		centerColumn.add(canvas, BorderLayout.CENTER);
		centerColumn.add(runControls, BorderLayout.SOUTH);
		JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerColumn, dock);
		leftSplit.setResizeWeight(0.75);
		JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatColumn, leftSplit);
		mainSplit.setResizeWeight(0.3);
		JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainSplit, tree);
		outer.setResizeWeight(0.8);

		JFrame frame = new JFrame("agentic research buddy");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setContentPane(outer);
		frame.setSize(1280, 800);
		frame.setLocationRelativeTo(null);
		return frame;
	}

	private static void runQuietly(Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			// gate refusals are expected user-facing events; the dock/chat carry them
			System.out.println("control refused: " + e.getMessage());
		}
	}

	private static void reportState(javax.swing.JLabel gateLabel, StudioActions actions, Runnable action) {
		runQuietly(action);
		gateLabel.setText("gate: " + actions.gateState() + "   actor: user");
	}
}
