package org.mobicents.media.core.configuration;

public class ASRConfiguration {

    private String azureKey;
    private String azureRegion;

    public ASRConfiguration() {
        this.azureKey = "";
        this.azureRegion = "";
    }

    public String getAzureKey() {
        return azureKey;
    }

    public void setAzureKey(String azureKey) {
        this.azureKey = azureKey;
    }

    public String getAzureRegion() {
        return azureRegion;
    }

    public void setAzureRegion(String azureRegion) {
        this.azureRegion = azureRegion;
    }
}
