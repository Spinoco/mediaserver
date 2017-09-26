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

package org.mobicents.media.server.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.log4j.Logger;

/**
 * Scheduling task.
 * 
 * @author Oifa Yulian
 */
public abstract class Task implements Runnable {

    private volatile boolean isActive = true;
    //error handler instance
    protected TaskListener listener;
    
    private final Object LOCK = new Object();    
        
    private AtomicBoolean inQueue=new AtomicBoolean(false);

    private Logger logger = Logger.getLogger(Task.class);
    
    public Task() { }

    public void storedInQueue()
    {
    	inQueue.set(true);
    }

    public void removeFromQueue()
    {
    	inQueue.set(false);
    }

    public Boolean isInQueue()
    {
    	return inQueue.get();
    }
    
    /**
     * Modifies task listener.
     * 
     * @param listener the handler instance.
     */
    public void setListener(TaskListener listener) {
        this.listener = listener;
    }
    
    /**
     * Current queue of this task.
     * 
     * @return the value of queue
     */
    public abstract EventQueueType getQueueType();
    
    
    /**
     * Executes task.
     * 
     * @return dead line of next execution
     */
    public abstract long perform();

    /**
     * Cancels task execution
     */
    public void cancel() {
    	synchronized(LOCK) {
    		this.isActive = false;    		
    	}
    }

    //call should not be synchronized since can run only once in queue cycle
    public void run() {
    		if (this.isActive)  {
    			try {
    				perform();                
                
    				//notify listener                
    				if (this.listener != null) {
    					this.listener.onTerminate();
    				}
    				
    			} catch (Exception e) {
    			    logger.error("Could not execute task: "+ e.getMessage(), e);
    				if (this.listener != null) {
    					listener.handlerError(e);
    				} 
    			}
    		}      		    		    	
    }

    protected void activateTask() {
    	synchronized(LOCK) {
    		this.isActive = true;
    	}
    }    
}
