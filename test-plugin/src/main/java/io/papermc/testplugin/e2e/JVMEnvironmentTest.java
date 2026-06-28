package io.papermc.testplugin.e2e;

public class JVMEnvironmentTest implements E2ETest {

    @Override
    public String getName() {
        return "JVMEnvironmentTest";
    }

    @Override
    public void run(int session) throws Exception {
        // 1. Verify Java Version >= 25
        int featureVersion = Runtime.version().feature();
        if (featureVersion < 25) {
            throw new IllegalStateException("Expected Java 25 or higher, but got Java " + featureVersion);
        }

        // 2. Verify Vector API module (jdk.incubator.vector) is loadable
        try {
            Class<?> vectorSpeciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
            if (vectorSpeciesClass == null) {
                throw new IllegalStateException("VectorSpecies class resolved to null");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jdk.incubator.vector module is not enabled or accessible. Please add --add-modules jdk.incubator.vector", e);
        }

        // 3. Verify Project Panama FFM (Foreign Function & Memory) API is usable
        try {
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
            java.lang.foreign.MemorySegment segment = arena.allocate(1024);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 42);
            int val = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
            arena.close();
            if (val != 42) {
                throw new IllegalStateException("FFM Arena memory allocation verification failed. Expected 42, got " + val);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Project Panama FFM API is not accessible or failed: " + t.getMessage(), t);
        }
    }
}
