package net.minecraft.world.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Alternate Current style BFS deterministic redstone wire handler.
 * Replaces Vanilla O(N^2) recursive updates with O(N) BFS topological sort.
 */
public class DeterministicRedstoneWireHandler {

    // Block update flags in Mojang Mappings:
    // 2: UPDATE_CLIENTS (NOTIFY_LISTENERS)
    // 16: UPDATE_KNOWN_SHAPE
    // 32: UPDATE_SUPPRESS_DROPS (SKIP_DROPS)
    // 48: 16 | 32 (Shape update + suppress drops)
    private static final int OBSERVER_SAFE_FLAGS = 2 | 16 | 32;

    /**
     * Executes deterministic wire updates using coordinate sorting.
     */
    public void updateWirePower(Level level, BlockPos startPos, BlockState startState) {
        Queue<Node> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        queue.add(new Node(startPos, 0));
        visited.add(startPos.asLong());

        List<Node> currentDepthNodes = new ArrayList<>();
        int currentDepth = 0;

        while (!queue.isEmpty()) {
            Node node = queue.poll();

            if (node.depth() > currentDepth) {
                processDepth(level, currentDepthNodes);
                currentDepthNodes.clear();
                currentDepth = node.depth();
            }

            currentDepthNodes.add(node);

            // Explore neighbors
            for (BlockPos neighbor : getNeighbors(node.pos())) {
                long posLong = neighbor.asLong();
                if (!visited.contains(posLong) && isRedstoneWire(level, neighbor)) {
                    visited.add(posLong);
                    queue.add(new Node(neighbor, node.depth() + 1));
                }
            }
        }

        if (!currentDepthNodes.isEmpty()) {
            processDepth(level, currentDepthNodes);
        }
    }

    /**
     * Processes a single depth layer in the BFS tree deterministically.
     */
    private void processDepth(Level level, List<Node> nodes) {
        // Sorting by asLong() guarantees a 100% deterministic update order,
        // preventing directional/locational bugs completely.
        nodes.sort(Comparator.comparingLong(n -> n.pos().asLong()));

        for (Node node : nodes) {
            updatePower(level, node.pos());
        }
    }

    /**
     * Calculates and applies the new power level.
     */
    private void updatePower(Level level, BlockPos pos) {
        BlockState currentState = level.getBlockState(pos);
        if (currentState == null) return;
        
        // Assume logic calculates new power (Mocked for context)
        int newPower = calculateNewPower(level, pos, currentState);
        BlockState newState = currentState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER, newPower);

        if (currentState != newState) {
            // Apply observer-safe flags to prevent shape update cascading overhead
            level.setBlock(pos, newState, OBSERVER_SAFE_FLAGS);
        }
    }

    private int calculateNewPower(Level level, BlockPos pos, BlockState state) {
        // Mock implementation of power calculation
        return 15;
    }

    private boolean isRedstoneWire(Level level, BlockPos pos) {
        // Mock check for redstone wire
        return level.getBlockState(pos) != null && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE);
    }

    private List<BlockPos> getNeighbors(BlockPos pos) {
        return List.of(
                pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()
        );
    }

    private record Node(BlockPos pos, int depth) {}
}
