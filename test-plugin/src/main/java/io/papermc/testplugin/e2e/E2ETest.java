package io.papermc.testplugin.e2e;

public interface E2ETest {
    String getName();
    void run(int session) throws Exception;
}
