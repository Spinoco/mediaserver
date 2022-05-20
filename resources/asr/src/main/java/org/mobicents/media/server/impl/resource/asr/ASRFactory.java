package org.mobicents.media.server.impl.resource.asr;

import org.mobicents.media.server.impl.resource.asr.azure.AzureASR;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.pooling.PooledObjectFactory;

import java.util.concurrent.ExecutorService;

public class ASRFactory implements PooledObjectFactory<ASR> {

    private final PriorityQueueScheduler mediaScheduler;
    private final ExecutorService googleRunner;
    private final String azureKey;
    private final String azureRegion;


    public ASRFactory(PriorityQueueScheduler mediaScheduler, ExecutorService googleRunner, String azureKey, String azureRegion) {
        this.mediaScheduler = mediaScheduler;
        this.googleRunner = googleRunner;
        this.azureKey = azureKey;
        this.azureRegion = azureRegion;
    }

    @Override
    public ASR produce() {
        return new AzureASR("", this.mediaScheduler, azureKey, azureRegion);
    }
}
