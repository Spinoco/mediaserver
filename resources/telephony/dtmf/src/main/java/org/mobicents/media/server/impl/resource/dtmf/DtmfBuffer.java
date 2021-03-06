/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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

package org.mobicents.media.server.impl.resource.dtmf;

import java.io.Serializable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.Logger;

/**
 * Implements digit buffer.
 * 
 * @author Oifa Yulian
 */
public class DtmfBuffer implements Serializable {
    
    //The interval between two TDMF tones
    //measured in milliseconds
    public int interdigitInterval = 160;
    
    //Queue for buffered events
    private ConcurrentLinkedQueue<DtmfEventImpl> queue = new ConcurrentLinkedQueue();
    
    //buffer size
    private int size = 20;
    
    //The absolute time of last arrived tone measured in milliseconds
    //using wall clock
    private long lastActivity = System.currentTimeMillis();
    
    //last arrived tone
    private String lastSymbol;

    //Owner of this buffer
    private DetectorImpl detector;
    private final static Logger logger = org.apache.logging.log4j.LogManager.getLogger(DtmfBuffer.class);
    
    
    /**
     * Constructs new instance of this buffer.
     * 
     * @param detector tone detector
     */
    public DtmfBuffer(DetectorImpl detector) {
        this.detector = detector;
    }

    /**
     * Modifies the inter digit interval.
     * 
     * @param silence the interval measured in milliseconds
     */
    public void setInterdigitInterval(int silence) {
        this.interdigitInterval = silence;
    }

    /**
     * Gets the current inter digit interval
     * 
     * @return the time measured in milliseconds.
     */
    public int getInterdigitInterval() {
        return this.interdigitInterval;
    }

    /**
     * Handles inter digit intervals.
     * 
     * @param symbol received digit.
     */
    public void push(String symbol) {
        long now = System.currentTimeMillis();
        if (!symbol.equals(lastSymbol) || (now - lastActivity > interdigitInterval)) {            
            lastActivity = now;
            lastSymbol = symbol;
            
            detector.fireEvent(symbol);
        }
        else
        	lastActivity=now;
    }

    /**
     * Queues specified event.
     * 
     * @param evt the event to be queued.
     */
    protected void queue(DtmfEventImpl evt) {
        if (queue.size() == size) {
            queue.poll();
        }
        queue.offer(evt);
        logger.info(String.format("(%s) Buffer size: %d", detector.getName(), queue.size()));
    }
    
    /**
     * Flushes the buffer content.
     */
    public void flush() {
        logger.info(String.format("(%s) Flush, buffer size: %d", detector.getName(), queue.size()));
        for (DtmfEventImpl aQueue : queue) {
            detector.fireEvent(aQueue);
        }
    }
    
    /**
     * Clears buffer content
     */
    public void clear() {
        queue.clear();
    }
}
