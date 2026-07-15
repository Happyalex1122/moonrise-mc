# Architecture & Core Features

Moonrise-MC introduces significant architectural shifts from upstream Paper, geared toward high-performance compute and modern Java capabilities.

## Headless Server Execution

To ensure purely headless execution without unnecessary overhead, the legacy Swing GUI has been permanently removed from `DedicatedServer` and `Main`. 

This prevents the JVM from loading heavyweight UI-related classes, reducing the initial memory footprint and slightly improving startup times, which is highly beneficial for Docker containerization and headless VPS environments.

## DDRG Redstone Engine (Phase 4)

Moonrise-MC implements a completely custom, high-performance redstone engine known as **DDRG** (Phase 4).

- **Implementation**: Introduced via new source patches `DDRGIncrementalBuilder.java` and `DDRGRedstoneGraph.java`, transforming redstone processing into a highly efficient graph structure.
- **Hooks**: Due to the invasive nature of this optimization, the engine relies on manual patching for Redstone hooks into vanilla classes such as `Level.java`, `DiodeBlock.java`, and `RedStoneWireBlock.java`.

## Valhalla Prep

Looking toward the future of Java with Project Valhalla, Moonrise-MC is proactively refactoring key classes to act as inline/value classes.

- **ChunkPos Optimization**: `ChunkPos` has been refactored to prepare it as a Java `value class`. 
- **Compatibility**: To ensure smooth transitions and maintain compatibility across the broader codebase without breaking field access, compatibility accessors (`x()`, `z()`, and `longKey()`) have been introduced.
