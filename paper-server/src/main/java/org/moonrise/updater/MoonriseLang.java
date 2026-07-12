package org.moonrise.updater;

import net.minecraft.server.config.MoonriseConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class MoonriseLang {

    private static YamlConfiguration langConfig;
    private static final Map<String, String> defaultKo = new HashMap<>();
    private static final Map<String, String> defaultEn = new HashMap<>();

    static {
        defaultKo.put("updater.success", "\n[Moonrise] 플러그인 자동 업데이트 스캔 및 다운로드가 모두 완료되었습니다! (총 %d개 플러그인 교체 대기 중)\n");
        defaultKo.put("updater.no_updates", "[Moonrise] 모든 대중 플러그인이 이미 최신 버전입니다. 스캔을 종료합니다.");
        defaultKo.put("updater.downloaded", "[Moonrise] %s 플러그인의 최신 버전을 다운로드 완료했습니다. 다음 서버 재시작 시 자동으로 교체됩니다.");
        defaultKo.put("updater.failed", "[Moonrise] 업데이트 다운로드 실패: %s - %s");

        defaultEn.put("updater.success", "\n[Moonrise] Automatic plugin update scan and download complete! (Total %d plugins waiting for replacement)\n");
        defaultEn.put("updater.no_updates", "[Moonrise] All public plugins are already up to date. Scan finished.");
        defaultEn.put("updater.downloaded", "[Moonrise] %s latest version downloaded successfully. It will be applied on the next server restart.");
        defaultEn.put("updater.failed", "[Moonrise] Failed to download update: %s - %s");
    }

    public static void init() {
        loadLanguage(MoonriseConfig.language);
    }

    private static void loadLanguage(String lang) {
        File langFile = new File("plugins/Moonrise/lang", lang + ".yml");
        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        } else {
            langConfig = new YamlConfiguration();
        }
    }

    public static String get(String key) {
        if (langConfig.contains(key)) {
            return langConfig.getString(key);
        }

        String lang = MoonriseConfig.language;
        if ("en".equalsIgnoreCase(lang) && defaultEn.containsKey(key)) {
            return defaultEn.get(key);
        } else if (defaultKo.containsKey(key)) {
            return defaultKo.get(key);
        }
        return key; // Fallback to key itself
    }

    public static void downloadAndSetLanguageAsync(String lang) {
        new Thread(() -> {
            try {
                String downloadUrl = "https://raw.githubusercontent.com/Happyalex1122/moonrise-mc/main/lang/" + lang + ".yml";
                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() == 200) {
                    File langDir = new File("plugins/Moonrise/lang");
                    if (!langDir.exists()) {
                        langDir.mkdirs();
                    }
                    File outputFile = new File(langDir, lang + ".yml");

                    try (InputStream is = connection.getInputStream(); FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }

                    // Update config
                    MoonriseConfig.language = lang;
                    MoonriseConfig.save();
                    loadLanguage(lang);

                    System.out.println("[Moonrise] Language changed to: " + lang);
                } else {
                    System.out.println("[Moonrise] Failed to fetch language from GitHub. Server returned code: " + connection.getResponseCode());
                }
            } catch (Exception e) {
                System.out.println("[Moonrise] Error fetching language: " + e.getMessage());
            }
        }, "Moonrise-Lang-Fetcher").start();
    }
}
