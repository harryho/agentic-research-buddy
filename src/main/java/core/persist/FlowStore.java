package core.persist;

import core.model.FlowDocument;
import core.model.RequirementThread;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Open-format persistence: a flow is a plain zip package holding a JSON manifest (the whole
 * flow document) and the requirement thread as markdown. Human-readable when unzipped; no
 * opaque binaries.
 */
public class FlowStore {

	public static final String MANIFEST_ENTRY = "manifest.json";

	public static final String THREAD_ENTRY = "thread.md";

	private final java.util.List<Path> readOnlyRoots;

	public FlowStore() {
		this.readOnlyRoots = java.util.List.of();
	}

	/** Declares read-only roots; save refuses any target under them (the shipped demos). */
	public FlowStore(Path... readOnlyRoots) {
		this.readOnlyRoots = java.util.Arrays.stream(readOnlyRoots).map(Path::toAbsolutePath).map(Path::normalize)
				.toList();
	}

	public record LoadedFlow(FlowDocument document, RequirementThread thread) {
	}

	public void save(FlowDocument document, RequirementThread thread, Path file) {
		Path target = file.toAbsolutePath().normalize();
		for (Path root : readOnlyRoots) {
			if (target.startsWith(root)) {
				throw new IllegalStateException("path is under a read-only root: " + target);
			}
		}
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
				putEntry(zip, MANIFEST_ENTRY, document.toJson().getBytes(StandardCharsets.UTF_8));
				putEntry(zip, THREAD_ENTRY, thread.toMarkdown().getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public LoadedFlow load(Path file) {
		try {
			String manifest = null;
			String thread = null;
			try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					if (MANIFEST_ENTRY.equals(entry.getName())) {
						manifest = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					} else if (THREAD_ENTRY.equals(entry.getName())) {
						thread = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					}
				}
			}
			if (manifest == null || thread == null) {
				throw new IOException("flow package is missing manifest.json or thread.md: " + file);
			}
			return new LoadedFlow(FlowDocument.fromJson(manifest), RequirementThread.fromMarkdown(thread));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public byte[] manifestBytes(Path file) {
		return entryBytes(file, MANIFEST_ENTRY);
	}

	public byte[] threadBytes(Path file) {
		return entryBytes(file, THREAD_ENTRY);
	}

	private byte[] entryBytes(Path file, String entryName) {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entryName.equals(entry.getName())) {
					return zip.readAllBytes();
				}
			}
			throw new IOException("entry " + entryName + " not found in " + file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}
}
