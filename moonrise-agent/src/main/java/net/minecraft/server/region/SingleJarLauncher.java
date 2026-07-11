package net.minecraft.server.region;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SingleJarLauncher {

    public static void main(String[] args) throws Exception {
        System.out.println("[Launcher] Starting Optimized Paper Server Launcher...");

        // Get the path to the current running JAR (which contains both this launcher, the agent, and the embedded paper classes)
        Path thisJar = Paths.get(SingleJarLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        
        // Build the command to run the real server with this jar as the JavaAgent
        List<String> command = new ArrayList<>();
        // Try to get current java executable
        String javaHome = System.getProperty("java.home");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            javaBin = Paths.get(javaHome, "bin", "java.exe");
        }
        
        command.add(javaBin.toAbsolutePath().toString());
        
        // Add JavaAgent argument pointing to OURSELVES
        command.add("-javaagent:" + thisJar.toAbsolutePath().toString());
        
        // Optional: JVM flags for performance
        command.add("-XX:+UseG1GC");
        command.add("-XX:+UnlockExperimentalVMOptions");
        command.add("-XX:MaxGCPauseMillis=50");
        command.add("-XX:+AlwaysPreTouch");
        // To allow Javassist to patch internal classes in modern Java (17/21)
        command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");

        // Use this jar as the classpath, and run the actual Paper Main class
        command.add("-cp");
        command.add(thisJar.toAbsolutePath().toString());
        
        List<String> userArgs = Arrays.asList(args);
        boolean hasNogui = userArgs.contains("nogui") || userArgs.contains("--nogui");
        
        if (!hasNogui) {
            System.out.println("[Launcher] GUI mode detected. Suppressing vanilla GUI and enabling ModernGui.");
            command.add("-Dantigravity.gui=true");
        } else {
            System.out.println("[Launcher] Headless mode detected. ModernGui disabled.");
            command.add("-Dantigravity.gui=false");
        }

        command.add("org.bukkit.craftbukkit.Main");

        if (!hasNogui) {
            command.addAll(userArgs);
            command.add("nogui"); // Suppress vanilla GUI
        } else {
            command.addAll(userArgs);
        }

        System.out.println("[Launcher] Launching Paper Server with JavaAgent...");
        
        ProcessBuilder pb = new ProcessBuilder(command);
        // Inherit IO so the console works perfectly (stdin, stdout, stderr)
        pb.inheritIO();
        
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        System.out.println("[Launcher] Server exited with code " + exitCode);
        System.exit(exitCode);
    }
}
