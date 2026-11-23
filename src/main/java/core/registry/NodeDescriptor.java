package core.registry;

import java.util.List;

/**
 * A node type as data: purpose, typed parameters, wire arity, and whether the executor
 * reads the run seed. One descriptor feeds the palette, the property editor, and the
 * grounding digest — the descriptor is the contract.
 */
public record NodeDescriptor(String type, String family, String purpose, List<ParamSpec> params, int inputs,
		int outputs, boolean seedAware) {
}
