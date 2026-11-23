package core.registry;

/** One typed parameter of a node: the canvas property editor and grounding digest both read these. */
public record ParamSpec(String name, String type, String constraint, boolean required) {
}
