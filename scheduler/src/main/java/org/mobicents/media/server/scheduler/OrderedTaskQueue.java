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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements queue of tasks.
 * 
 * 
 * @author yulian oifa
 */
public class OrderedTaskQueue {
	//inner holder for tasks
    private ConcurrentLinkedQueue<Task> taskList = new ConcurrentLinkedQueue<Task>();


    // return number of available tasks to be processed
    private AtomicInteger available = new AtomicInteger(0);
    

    public OrderedTaskQueue() {
    }

    /**
     * Queues specified task using tasks dead line time.
     * 
     * @param task the task to be queued.
     * @return TaskExecutor for the scheduled task.
     */
    public void accept(Task task) {
        if(!task.isInQueue()) {
            taskList.offer(task);
            task.storedInQueue();
            available.incrementAndGet();
        }
    }
    
    /**
     * Retrieves the task with earliest dead line and removes it from queue.
     * 
     * @return task which has earliest dead line
     */
    public Task poll() {
    	Task result = taskList.poll();
    	result.removeFromQueue();
    	return result;
    }

    /**
     * Gets number of available tasks to be ready for processing
     * @return
     */
    public int getAvailable() {
        return available.getAndSet(0);
    }
    
    /**
     * Clean the queue.
     */
    public void clear() {
        taskList.clear();
    }
}
