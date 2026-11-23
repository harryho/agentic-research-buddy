package core.persist;

import core.engine.NodeContext;
import core.model.FlowDocument;
import core.model.Provenance;
import core.model.Wire;
import core.registry.NodeDescriptor;
import core.registry.NodeRegistry;
import core.registry.ParamSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The .rmls import slice: legacy flows (Java-beans XML of the ancestor studio's graph)
 * parse with a plain DOM walk - the legacy classes are never instantiated. Nodes whose
 * legacy key maps to a registry equivalent translate through the table below; unmapped
 * keys become inert placeholders that keep the legacy key and are named in warnings -
 * nothing is silently dropped. Edges name their endpoints directly (sourceId/targetId).
 * Export is out of scope; positions are presentation and travel separately from the
 * document content.
 */
public final class RmlsImportAdapter {

	/** legacy key -> registry-equivalent node type (the translation table). */
	private static final Map<String, String> NODE_TYPES = Map.of(
			"dataModel.importData", "import-csv",
			"estimation.ols", "ols-regression",
			"evaluation.rsquare", "eval-regression",
			"evaluation.pvalue", "eval-regression",
			"evaluation.accuracy", "eval-classification",
			"evaluation.confusion", "eval-classification");

	public record Imported(FlowDocument document, List<String> warnings, Map<String, double[]> positions) {
	}

	private RmlsImportAdapter() {
	}

	/** Registers the inert placeholder type - pure data through the public extension seam. */
	public static synchronized NodeRegistry withPlaceholder(NodeRegistry registry) {
		boolean already = registry.descriptors().stream()
				.anyMatch(d -> d.type().equals("legacy-note"));
		if (!already) {
			registry.register(
					new NodeDescriptor("legacy-note", "legacy",
							"inert placeholder for a legacy node the import could not map",
							List.of(new ParamSpec("legacyKey", "string",
									"the legacy node key kept for reference", false)),
							1, 1, false),
					(core.model.Node node, NodeContext context) -> context.soleInput());
		}
		return registry;
	}

	public static Imported importFlow(Path file) throws IOException {
		Document dom;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			dom = factory.newDocumentBuilder().parse(file.toFile());
		} catch (Exception e) {
			throw new IOException("not a parseable legacy flow: " + file.getFileName()
					+ " (" + e.getMessage() + ")", e);
		}
		List<String> warnings = new ArrayList<>();
		List<core.model.Node> nodes = new ArrayList<>();
		Map<String, double[]> positions = new LinkedHashMap<>();

		// cells: RMGraphCell objects carry key, id, position, and the ATTR_* parameter map
		NodeList objects = dom.getElementsByTagName("object");
		int legacyIndex = 0;
		for (int i = 0; i < objects.getLength(); i++) {
			Element object = (Element) objects.item(i);
			if (!"com.rm.app.graph.RMGraphCell".equals(object.getAttribute("class"))) {
				continue;
			}
			legacyIndex++;
			String legacyKey = firstPropertyString(object, "key");
			String cellRef = firstPropertyString(object, "id");
			if (cellRef == null || cellRef.isBlank()) {
				cellRef = "legacy-" + legacyIndex;
			}
			String ourType = legacyKey == null ? null : NODE_TYPES.get(legacyKey);
			String nodeId = cellRef;
			Map<String, Object> params = new LinkedHashMap<>();
			if (ourType != null) {
				Map<String, String> attrs = attributeMap(object);
				switch (ourType) {
					case "import-csv" -> {
						String location = resolve(attrs, "Data location", "ATTR_DATA_MODEL_LOCATION");
						if (location == null || location.isBlank()) {
							warnings.add("dataModel.importData (" + cellRef + ") carries no dataset"
									+ " location; set the path before running");
						} else {
							int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
							params.put("path", slash < 0 ? location : location.substring(slash + 1));
							warnings.add("dataset '" + params.get("path") + "' must exist in the"
									+ " workspace (legacy location: " + location + ")");
						}
					}
					case "ols-regression" -> {
						String target = resolve(attrs, "Dependent Variable", "DEPENDENT");
						String features = resolve(attrs, "Independent Variable", "INDEPENDENT");
						if (target != null && features != null) {
							params.put("target", target);
							params.put("features", features);
						} else {
							warnings.add("estimation.ols (" + cellRef
									+ ") carries no formula; set target and features before running");
						}
					}
					case "eval-regression", "eval-classification" -> {
						String actual = resolve(attrs, "Reference variable", "ATTR_EVAL_REF_VAR",
								"Dependent Variable", "DEPENDENT");
						String predicted = resolve(attrs, "Predicted", "ATTR_EVAL_PRED", "PREDICTED");
						if (actual != null && predicted != null) {
							params.put("actual", actual);
							params.put("predicted", predicted);
						} else {
							warnings.add(legacyKey + " (" + cellRef + ") lacks its column attributes;"
									+ " set actual and predicted before running");
						}
					}
					default -> throw new IllegalStateException("unmapped translation: " + ourType);
				}
			} else {
				ourType = "legacy-note";
				params.put("legacyKey", legacyKey == null ? "unknown" : legacyKey);
				warnings.add("legacy node '" + legacyKey + "' (" + cellRef + ") has no registry"
						+ " equivalent; imported as an inert placeholder");
			}
			nodes.add(new core.model.Node(nodeId, ourType, params));
			Element posX = propertyVoid(object, "posX");
			Element posY = propertyVoid(object, "posY");
			if (posX != null && posY != null) {
				try {
					positions.put(nodeId, new double[] {
							Double.parseDouble(text(posX)), Double.parseDouble(text(posY)) });
				} catch (NumberFormatException ignored) {
					// layout is presentation; an unusable position is skipped, not fatal
				}
			}
		}

		// edges: the legacy format names its endpoints directly (sourceId/targetId)
		List<Wire> wires = new ArrayList<>();
		Set<String> seenWires = new LinkedHashSet<>();
		for (int i = 0; i < objects.getLength(); i++) {
			Element object = (Element) objects.item(i);
			if (!"com.rm.app.graph.RMGraphEdge".equals(object.getAttribute("class"))) {
				continue;
			}
			String from = firstPropertyString(object, "sourceId");
			String to = firstPropertyString(object, "targetId");
			if (from != null && to != null && seenWires.add(from + "->" + to)) {
				wires.add(new Wire(from, to));
			} else if (from == null || to == null) {
				warnings.add("a legacy edge could not be resolved to cells and was skipped");
			}
		}

		if (nodes.isEmpty()) {
			throw new IOException("no legacy cells found in " + file.getFileName());
		}
		String name = file.getFileName() == null ? "imported" : file.getFileName().toString()
				.replaceAll("\\.rmls$", "");
		FlowDocument document = new FlowDocument(name, 1, List.copyOf(nodes), List.copyOf(wires), 42L,
				new Provenance("imported", "legacy:" + file.getFileName(), java.time.Instant.now().toString()),
				"thread-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-"), List.of());
		return new Imported(document, List.copyOf(warnings), Map.copyOf(positions));
	}

	/** The property's <void> wrapper - string, double, and object values live under it. */
	private static Element propertyVoid(Element parent, String name) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child instanceof Element element && "void".equals(element.getTagName())
					&& name.equals(element.getAttribute("property"))) {
				return element;
			}
		}
		return null;
	}

	private static String firstPropertyString(Element parent, String name) {
		Element property = propertyVoid(parent, name);
		return property == null ? null : text(property);
	}

	private static String text(Element propertyVoid) {
		NodeList children = propertyVoid.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child instanceof Element element
					&& ("string".equals(element.getTagName()) || "double".equals(element.getTagName()))) {
				return element.getTextContent();
			}
		}
		return null;
	}

	/**
	 * Every string-valued attribute of a cell: the visual attribute map and the model's
	 * dataMap merged, keyed as the legacy file spells them (producers vary: readable
	 * names, ATTR_* constants, uppercase semantic keys).
	 */
	private static Map<String, String> attributeMap(Element cell) {
		Map<String, String> attrs = new LinkedHashMap<>();
		mergePuts(cell, "attributes", attrs);
		mergePuts(cell, "dataMap", attrs);
		return attrs;
	}

	/** First candidate key present in the map, any casing. */
	private static String resolve(Map<String, String> attrs, String... candidates) {
		for (String candidate : candidates) {
			String value = attrs.get(candidate);
			if (value == null) {
				value = attrs.get(candidate.toLowerCase(Locale.ROOT));
			}
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static void mergePuts(Element cell, String property, Map<String, String> attrs) {
		Element attributes = propertyVoid(cell, property);
		if (attributes == null) {
			return;
		}
		NodeList puts = attributes.getChildNodes();
		for (int i = 0; i < puts.getLength(); i++) {
			Node putNode = puts.item(i);
			if (!(putNode instanceof Element put) || !"void".equals(put.getTagName())
					|| !"put".equals(put.getAttribute("method"))) {
				continue;
			}
			String key = null;
			String plainValue = null;
			Element attributeObject = null;
			NodeList putChildren = put.getChildNodes();
			for (int j = 0; j < putChildren.getLength(); j++) {
				Node putChild = putChildren.item(j);
				if (putChild instanceof Element element && "string".equals(element.getTagName())) {
					if (key == null) {
						key = element.getTextContent();
					} else if (plainValue == null) {
						plainValue = element.getTextContent();
					}
				} else if (putChild instanceof Element element && "object".equals(element.getTagName())
						&& element.getAttribute("class").contains("Attribute")) {
					attributeObject = element;
				}
			}
			if (key == null) {
				continue;
			}
			if (attributeObject != null) {
				String value = firstPropertyString(attributeObject, "value");
				if (value != null) {
					attrs.put(key, value);
				}
			} else if (plainValue != null) {
				attrs.put(key, plainValue);
			}
		}
	}
}
