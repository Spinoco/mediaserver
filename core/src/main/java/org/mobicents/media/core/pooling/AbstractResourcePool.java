/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2015, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.core.pooling;

import java.util.Queue;

/**
 * Abstraction of a {@link ResourcePool} that relies on an internal queue to maintain the collection of resources.
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public abstract class AbstractResourcePool<T extends PooledObject> implements ResourcePool<T> {

    private final Queue<T> resources;

    protected AbstractResourcePool(Queue<T> resources, int initialCapacity) {
        this.resources = resources;
        for(int index=0; index < initialCapacity; index++) {
            this.resources.offer(createResource());
        }
    }

    @Override
    public T poll() {
        // Get resource
        T resource = resources.poll();
        if (resource == null) {
            resource = createResource();
        }

        // Initialize state of the resource
        resource.checkOut();
        return resource;
    }

    @Override
    public void offer(T resource) {
        if (resource != null) {
            // Reset state of the object
            resource.checkIn();

            // Place object back into the pool
            this.resources.offer(resource);
        }
    }

    @Override
    public void release() {
        this.resources.clear();
    }
    
    @Override
    public int count() {
        return this.resources.size();
    }
    
    @Override
    public boolean isEmpty() {
        return this.resources.isEmpty();
    }

    protected abstract T createResource();

}
