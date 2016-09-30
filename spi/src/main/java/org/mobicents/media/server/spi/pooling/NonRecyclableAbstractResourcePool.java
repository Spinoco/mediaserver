package org.mobicents.media.server.spi.pooling;

import org.apache.log4j.Logger;

/**
 * ResourcePool, that turns off any recycling of the resources. We always create fresh new resource on every acquisition
 */
public abstract class NonRecyclableAbstractResourcePool<T extends PooledObject>  implements ResourcePool<T> {


    @Override
    public T poll() {
        T resource = createResource();
        resource.checkOut();
        return resource;
    }

    @Override
    public void offer(T resource) {
        if (resource != null) {
            resource.checkIn();
        }
    }

    @Override
    public void release() {
    }

    @Override
    public int count() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int size() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    protected abstract T createResource();
}
