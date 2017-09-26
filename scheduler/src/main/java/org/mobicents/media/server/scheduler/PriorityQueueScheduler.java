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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


import org.apache.log4j.Logger;

/**
 * Implements scheduler with multi-level priority queue.
 *
 * This scheduler implementation follows to uniprocessor model with "super" thread.
 * The "super" thread includes IO bound thread and one or more CPU bound threads
 * with equal priorities.
 *
 * The actual priority is assigned to task instead of process and can be
 * changed dynamically at runtime using the initial priority level, feedback
 * and other parameters.
 *
 *
 * @author Oifa Yulian
 */
public class PriorityQueueScheduler  {

    //The clock for time measurement
    private Clock clock;

    //priority queue
    protected OrderedTaskQueue[] taskQueues = new OrderedTaskQueue[EventQueueType.values().length];


    private Logger logger = Logger.getLogger(PriorityQueueScheduler.class) ;


    private class NamedThreadFactory implements ThreadFactory {

        private String name;

        private AtomicInteger idx = new AtomicInteger(0);

        public NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name + "-" + idx.incrementAndGet());
            return t;
        }
    }

    private ScheduledExecutorService schedulerV2 = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("ms-v2-scheduler"));

    // run any no real-time critical tasks here
    // we still keep 20ms counter here (perhaps not needed?), but there is no guarantee that tasks may be finished in 20ms
    // specifically, record and media player tasks may take longer time.
    private ExecutorService workerExecutorV2 =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4, new NamedThreadFactory("ms-v2-worker"));

    // real time tasks here, that need to assure there will be no
    // task blocking the thread scheduler.
    // this shall assure that any activity here is performed in much less than 20m.
    private ExecutorService priorityExecutorV2 =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2, new NamedThreadFactory("ms-v2-worker-priority"));


    private class DrainQueue implements Runnable {
        private OrderedTaskQueue queue;
        private EventQueueType tpe;
        private ExecutorService es;

        public DrainQueue(OrderedTaskQueue queue, EventQueueType tpe, ExecutorService es) {
            this.queue = queue;
            this.tpe = tpe;
            this.es = es;
        }

        @Override
        public void run() {
            int remains = queue.size();
            while (remains > 0) {
                final Task task = queue.poll();
                if (task != null) {
                    workerExecutorV2.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                task.run();
                            } catch (Exception ex) {
                                logger.error("Failed to execute task: " + task + " " + DrainQueue.this.tpe, ex);
                            }
                        }
                    });
                } else {
                    logger.warn("Failed to execute task, task is null. " + this.tpe);
                }
                remains --;
            }

        }
    }


    /**
     * Creates new instance of scheduler.
     */
    public PriorityQueueScheduler(Clock clock) {
        this.clock = clock;

    	for(int i=0;i<taskQueues.length;i++) {
    		taskQueues[i]=new OrderedTaskQueue();
    	}

    }
    
    public PriorityQueueScheduler() {
        this(null);
    }


    
    /**
     * Sets clock.
     *
     * @param clock the clock used for time measurement.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Gets the clock used by this scheduler.
     *
     * @return the clock object.
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Queues task for execution according to its priority.
     *
     * @param task the task to be executed.
     */
    public void submit(Task task,EventQueueType tpe) {
        task.activateTask();
        taskQueues[tpe.ordinal()].accept(task);
    }
    
    /**
     * Queues task for execution according to its priority.
     *
     * @param task the task to be executed.
     */
    public void submitHeartbeat(Task task) {
        task.activateTask();
        taskQueues[EventQueueType.HEARTBEAT.ordinal()].accept(task);
    }
    
    /**
     * Queues chain of the tasks for execution.
     *
     */
    public void submit(TaskChain taskChain) {    	
        taskChain.start();
    }    
    
    /**
     * Starts scheduler.
     */
    public void start() {

        if (clock == null) {
            throw new IllegalStateException("Clock is not set");
        }

        logger.info("Starting Scheduler Task Queues ");

        for (int i = 0; i < EventQueueType.values().length; i++) {

            ExecutorService es = workerExecutorV2;
            EventQueueType tpe = EventQueueType.values()[i];
            long delay = 20;
            logger.info("Starting task queue: " + tpe);

            switch (tpe) {
                case RTP_INPUT :
                    es = priorityExecutorV2;
                    break;
                case RTP_OUTPUT:
                    es = priorityExecutorV2;
                    break;
                case RTP_MIXER:
                    es =priorityExecutorV2;
                    break;
                case HEARTBEAT:
                    delay = 100;
                    break;

            }
            schedulerV2.scheduleAtFixedRate(new DrainQueue(taskQueues[i], tpe, es), delay, delay, TimeUnit.MILLISECONDS);
        }

        logger.info("Started All Scheduler Task Queues");
    }

    /**
     * Stops scheduler.
     */
    public void stop() {
        logger.info("Shutting down Scheduler Task Queues");
        schedulerV2.shutdown();
        workerExecutorV2.shutdown();
        priorityExecutorV2.shutdown();

    }



}
