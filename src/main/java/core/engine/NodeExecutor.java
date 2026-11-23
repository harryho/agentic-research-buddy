package core.engine;

import core.model.Node;

/** A node executor: pure computation over its parameters and the upstream output. */
@FunctionalInterface
public interface NodeExecutor {

	Object execute(Node node, NodeContext context) throws Exception;
}
