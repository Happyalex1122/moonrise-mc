package net.minecraft.server.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

public class ConsoleSpamFilter extends AbstractFilter {
    private static final int MAX_DUPLICATES = 3;
    private static final long RESET_TIME_MS = 5000L;

    private String lastMessage = "";
    private int duplicateCount = 0;
    private long lastLogTime = 0L;

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        String msg = event.getMessage().getFormattedMessage();
        
        long now = System.currentTimeMillis();
        if (now - lastLogTime > RESET_TIME_MS) {
            duplicateCount = 0;
            lastMessage = msg;
            lastLogTime = now;
            return Result.NEUTRAL;
        }

        if (msg.equals(lastMessage)) {
            duplicateCount++;
            if (duplicateCount >= MAX_DUPLICATES) {
                return Result.DENY; // Suppress spam
            }
        } else {
            lastMessage = msg;
            duplicateCount = 0;
            lastLogTime = now;
        }

        return Result.NEUTRAL;
    }

    public static void inject() {
        try {
            org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();
            ConsoleSpamFilter filter = new ConsoleSpamFilter();
            filter.start();
            config.addFilter(filter);
            ctx.updateLoggers();
            System.out.println("[ConsoleSpamFix] Global Log4j2 Spam Filter injected successfully. Repeating logs will be suppressed.");
        } catch (Throwable t) {
            System.out.println("[ConsoleSpamFix] Could not inject Log4j2 filter: " + t.getMessage());
        }
    }
}
