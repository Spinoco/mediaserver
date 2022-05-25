package org.mobicents.media.server.impl.resource.asr;

import org.mobicents.media.server.spi.pooling.NonRecyclableAbstractResourcePool;
import org.mobicents.media.server.spi.pooling.PooledObjectFactory;

public class ASRPool extends NonRecyclableAbstractResourcePool<ASR> {

    private final PooledObjectFactory<ASR> factory;

    public ASRPool(PooledObjectFactory<ASR> factory) {
        this.factory = factory;
    }

    @Override
    protected ASR createResource() {
        return factory.produce();
    }
}
