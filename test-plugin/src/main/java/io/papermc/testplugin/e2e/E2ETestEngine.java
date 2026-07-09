package io.papermc.testplugin.e2e;

import org.bukkit.Bukkit;
import java.util.List;

public class E2ETestEngine {

    private static final List<E2ETest> TESTS = List.of(
        new JVMEnvironmentTest(),
        new LMDBOperationsTest()
    );

    public static void run() {
        String sessionProp = System.getProperty("runE2ETests.session", "1");
        int session = 1;
        try {
            session = Integer.parseInt(sessionProp);
        } catch (NumberFormatException e) {
            System.out.println("[WARN] Invalid runE2ETests.session property: " + sessionProp + ". Defaulting to 1.");
        }

        System.out.println("=== Starting Moonrise E2E Tests (Session " + session + ") ===");

        int passedCount = 0;
        int failedCount = 0;

        for (E2ETest test : TESTS) {
            String name = test.getName();
            System.out.println("[E2E-TEST] START: " + name);
            try {
                test.run(session);
                System.out.println("[E2E-TEST] PASS: " + name);
                passedCount++;
            } catch (Throwable t) {
                System.out.println("[E2E-TEST] FAIL: " + name + " - " + t.getMessage());
                t.printStackTrace(System.out);
                failedCount++;
            }
        }

        System.out.println("[E2E-TEST] ALL TESTS COMPLETE - PASS: " + passedCount + " FAIL: " + failedCount);

        // Gracefully shutdown the server
        System.out.println("E2ETestEngine finished tests. Waiting for stress metrics...");
        // Bukkit.shutdown();
    }
}
