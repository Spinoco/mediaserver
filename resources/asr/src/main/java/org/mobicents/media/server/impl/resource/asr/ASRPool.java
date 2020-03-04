package org.mobicents.media.server.impl.resource.asr;

import org.mobicents.media.server.spi.pooling.NonRecyclableAbstractResourcePool;
import org.mobicents.media.server.spi.pooling.PooledObjectFactory;

public class ASRPool extends NonRecyclableAbstractResourcePool<ASRImpl> {

    private final PooledObjectFactory<ASRImpl> factory;

    public ASRPool(PooledObjectFactory<ASRImpl> factory) {
        this.factory = factory;
    }

    @Override
    protected ASRImpl createResource() {
        return factory.produce();
    }
}
