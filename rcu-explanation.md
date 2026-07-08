# PR 1: RCU Data Structures (Option 3)

This PR implements the requested `BatchedRcuEntityList` and `RcuList` structures and integrates them into `EntityTickList`.

It allows zero-wait time reading of entities during ticking using `VarHandle.getAcquire()`, completely eliminating locking contention during `add`/`remove` from async pathfinders or chunk loaders.

Modifications:
- Added `BatchedRcuEntityList` and `RcuList` to `ca.spottedleaf.moonrise.common.list`.
- Updated `EntityTickList` to use the batched RCU list for iterating entities.
- Updated `MinecraftServerGui` to fix a compilation issue that popped up during build.

The server has been compiled and patches rebuilt.
