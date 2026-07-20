package org.moonrise.updater.safev1;

import java.util.List;
import java.util.UUID;

public class UpdateManifest {
    private String batchId;
    private UpdateState currentState;
    private List<Candidate> plugins;

    public UpdateManifest() {
        this.batchId = UUID.randomUUID().toString();
        this.currentState = UpdateState.UNKNOWN;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public UpdateState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(UpdateState currentState) {
        this.currentState = currentState;
    }

    public List<Candidate> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<Candidate> plugins) {
        this.plugins = plugins;
    }

    public static class Candidate {
        private String name;
        private String version;
        private String sourceUrl;
        
        public Candidate() {}

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public void setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
        }
        
        private String expectedHash;
        
        public String getExpectedHash() {
            return expectedHash;
        }
        
        public void setExpectedHash(String expectedHash) {
            this.expectedHash = expectedHash;
        }
    }
}
