package org.mobicents.media.server.impl.resource.asr;

import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.pooling.PooledObjectFactory;

import java.util.concurrent.ExecutorService;

public class ASRFactory implements PooledObjectFactory<ASRImpl> {

    private final PriorityQueueScheduler mediaScheduler;
    private final ExecutorService googleRunner;

    public ASRFactory(PriorityQueueScheduler mediaScheduler, ExecutorService googleRunner) {
        this.mediaScheduler = mediaScheduler;
        this.googleRunner = googleRunner;
    }

    @Override
    public ASRImpl produce() {
        return new ASRImpl("", this.mediaScheduler, this.googleRunner);
    }
}
