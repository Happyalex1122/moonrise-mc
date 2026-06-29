package io.papermc.testplugin;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);

        // io.papermc.testplugin.brigtests.Registration.registerViaOnEnable(this);

        this.getLogger().info("System property runE2ETests: " + System.getProperty("runE2ETests"));
        this.getLogger().info("System property runE2ETests.session: " + System.getProperty("runE2ETests.session"));

        if (Boolean.getBoolean("runE2ETests")) {
            this.getLogger().info("runE2ETests=true detected. Scheduling E2ETests run one tick later...");
            this.getServer().getScheduler().runTaskLater(this, io.papermc.testplugin.e2e.E2ETestEngine::run, 1L);
        }

        // Run the Moonrise stress test 10 seconds (200 ticks) after boot
        this.getServer().getScheduler().runTaskLater(this, () -> MoonriseStressTest.runStressTest(this), 200L);
    }
}
