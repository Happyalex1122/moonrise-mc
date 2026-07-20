package org.moonrise.updater.safev1;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class UpdateManifestTest {

    @Test
    public void testManifestDefaults() {
        UpdateManifest manifest = new UpdateManifest();
        assertNotNull(manifest.getBatchId());
        assertEquals(UpdateState.UNKNOWN, manifest.getCurrentState());
        assertNull(manifest.getPlugins());
    }

    @Test
    public void testManifestProperties() {
        UpdateManifest manifest = new UpdateManifest();
        manifest.setBatchId("batch-123");
        manifest.setCurrentState(UpdateState.STAGED);
        
        UpdateManifest.Candidate plugin = new UpdateManifest.Candidate();
        plugin.setName("TestPlugin");
        plugin.setVersion("1.0.0");
        plugin.setSourceUrl("http://example.com/plugin.jar");
        plugin.setExpectedHash("abc123hash");

        manifest.setPlugins(List.of(plugin));

        assertEquals("batch-123", manifest.getBatchId());
        assertEquals(UpdateState.STAGED, manifest.getCurrentState());
        assertEquals(1, manifest.getPlugins().size());
        
        UpdateManifest.Candidate fetched = manifest.getPlugins().get(0);
        assertEquals("TestPlugin", fetched.getName());
        assertEquals("1.0.0", fetched.getVersion());
        assertEquals("http://example.com/plugin.jar", fetched.getSourceUrl());
        assertEquals("abc123hash", fetched.getExpectedHash());
    }
}
