package core.persist;

import core.model.FlowDocument;
import core.model.RequirementThread;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The managed library: a read-only root of shipped demo flows plus user projects holding
 * immutable versions. The zip files on disk are the truth — listings, search, and versions
 * all derive from the tree, never from an opaque store.
 */
public class Library {

	private static final List<String> DEMOS = List.of("gradrate-ols", "iris-knn", "colon-svm");

	private final Path demoRoot;

	private final Path projectsRoot;

	private final FlowStore demoStore;

	public Library(Path workspace) throws IOException {
		this.demoRoot = workspace.resolve("library").resolve("demo");
		this.projectsRoot = workspace.resolve("library").resolve("projects");
		Files.createDirectories(demoRoot);
		Files.createDirectories(projectsRoot);
		FlowStore installer = new FlowStore();
		for (String demo : DEMOS) {
			Path target = demoRoot.resolve(demo + ".flow.zip");
			if (Files.notExists(target)) {
				installer.save(document(demo), thread(demo), target);
			}
		}
		this.demoStore = new FlowStore(demoRoot);
	}

	public List<String> demos() {
		try (Stream<Path> entries = Files.list(demoRoot)) {
			return entries.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".flow.zip"))
					.map(n -> n.substring(0, n.length() - ".flow.zip".length()))
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public FlowStore.LoadedFlow openDemo(String name) {
		return demoStore.load(safeChild(demoRoot, name + ".flow.zip"));
	}

	public Path demoRoot() {
		return demoRoot;
	}

	public List<String> projects() {
		try (Stream<Path> entries = Files.list(projectsRoot)) {
			return entries.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void createProject(String name) throws IOException {
		Files.createDirectories(projectDir(name));
	}

	public FlowDocument copyDemo(String demo, String project) throws IOException {
		FlowStore.LoadedFlow loaded = openDemo(demo);
		return saveVersion(project, loaded.document(), loaded.thread());
	}

	/**
	 * Version bump semantics: a re-confirm whose content hash changed materializes a NEW
	 * immutable version and bumps the document's version number; an unchanged re-confirm is a
	 * no-op. Version 1 is the initial save.
	 */
	public FlowDocument saveVersion(String project, FlowDocument document, RequirementThread thread)
			throws IOException {
		Path dir = projectDir(project);
		Files.createDirectories(dir);
		List<Integer> existing = versions(project);
		if (!existing.isEmpty()) {
			FlowDocument last = openVersion(project, existing.get(existing.size() - 1));
			if (last.contentHash().equals(document.contentHash())) {
				return document;
			}
		}
		int next = existing.isEmpty() ? 1 : existing.get(existing.size() - 1) + 1;
		FlowDocument versioned = new FlowDocument(document.name(), next, document.nodes(), document.wires(),
				document.seed(), document.provenance(), document.threadId(), document.confirmHistory());
		new FlowStore().save(versioned, thread, dir.resolve("v" + next + ".flow.zip"));
		return versioned;
	}

	public List<Integer> versions(String project) {
		try (Stream<Path> entries = Files.list(projectDir(project))) {
			return entries.map(p -> p.getFileName().toString())
					.filter(n -> n.matches("v\\d+\\.flow\\.zip"))
					.map(n -> Integer.parseInt(n.substring(1, n.indexOf('.'))))
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public FlowDocument openVersion(String project, int version) {
		return openVersionFlow(project, version).document();
	}

	public RequirementThread openVersionThread(String project, int version) {
		return openVersionFlow(project, version).thread();
	}

	private FlowStore.LoadedFlow openVersionFlow(String project, int version) {
		return new FlowStore().load(projectDir(project).resolve("v" + version + ".flow.zip"));
	}

	/** Case-insensitive search over demo names, project names, and thread text. */
	public List<String> search(String query) {
		String q = query.toLowerCase();
		List<String> hits = new ArrayList<>();
		for (String demo : demos()) {
			FlowStore.LoadedFlow loaded = openDemo(demo);
			if (demo.toLowerCase().contains(q) || loaded.thread().toMarkdown().toLowerCase().contains(q)) {
				hits.add("demo:" + demo);
			}
		}
		for (String project : projects()) {
			boolean hit = project.toLowerCase().contains(q);
			for (int version : versions(project)) {
				FlowStore.LoadedFlow loaded = new FlowStore()
						.load(projectDir(project).resolve("v" + version + ".flow.zip"));
				if (!hit && (loaded.document().name().toLowerCase().contains(q)
						|| loaded.thread().toMarkdown().toLowerCase().contains(q))) {
					hit = true;
				}
			}
			if (hit) {
				hits.add("project:" + project);
			}
		}
		return hits;
	}

	/** The project folder - tests and the packaged smoke assert artifacts against it. */
	public Path projectDir(String project) {
		return safeChild(projectsRoot, project);
	}

	private static Path safeChild(Path base, String name) {
		return Names.safeChild(base, name);
	}

	private FlowDocument document(String demo) {
		String json = resource("demo/" + demo + ".flow.json");
		return FlowDocument.fromJson(json);
	}

	private RequirementThread thread(String demo) {
		return RequirementThread.fromMarkdown(resource("demo/" + demo + ".thread.md"));
	}

	private String resource(String name) {
		try (var in = Library.class.getResourceAsStream("/" + name)) {
			if (in == null) {
				throw new IOException("missing classpath resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
