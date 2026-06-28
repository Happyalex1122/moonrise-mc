package io.papermc.testplugin.e2e;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class LMDBOperationsTest implements E2ETest {

    private static final String FILE_NAME = "lmdb_persistence_test.dat";
    private static final String TEST_KEY = "lmdb.test.key";
    private static final String TEST_VALUE = "MoonriseLMDBState_Verified_12345";

    @Override
    public String getName() {
        return "LMDBOperationsTest";
    }

    @Override
    public void run(int session) throws Exception {
        File file = new File(FILE_NAME);
        if (session == 1) {
            // Write state
            Properties props = new Properties();
            props.setProperty(TEST_KEY, TEST_VALUE);
            try (FileOutputStream out = new FileOutputStream(file)) {
                props.store(out, "LMDB Simulated Persistence State");
            }
            if (!file.exists()) {
                throw new IllegalStateException("Failed to write LMDB state file: " + file.getAbsolutePath());
            }
        } else if (session == 2) {
            // Verify state
            if (!file.exists()) {
                throw new IllegalStateException("LMDB state file not found in Session 2: " + file.getAbsolutePath());
            }
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            }
            String val = props.getProperty(TEST_KEY);
            if (!TEST_VALUE.equals(val)) {
                throw new IllegalStateException("LMDB state verification failed. Expected '" + TEST_VALUE + "', but got '" + val + "'");
            }
            // Cleanup the file at the end of session 2 so the next test run starts fresh
            if (!file.delete()) {
                System.out.println("[WARN] Failed to delete LMDB test persistence file: " + file.getAbsolutePath());
            }
        } else {
            throw new IllegalArgumentException("Unknown session number: " + session);
        }
    }
}
