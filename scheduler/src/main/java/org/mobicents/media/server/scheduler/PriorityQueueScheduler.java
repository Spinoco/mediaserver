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

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


import org.apache.logging.log4j.Logger;

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

    // Queues for RTP Tasks, real time 20ms timer
    protected OrderedTaskQueue rtpInputQ = new OrderedTaskQueue();
    protected OrderedTaskQueue rtpMixerQ = new OrderedTaskQueue();
    protected OrderedTaskQueue rtpOuputQ = new OrderedTaskQueue();

    // queues for heartbeat, 100ms, nest effort
    protected OrderedTaskQueue heartbeatQ = new OrderedTaskQueue();



    private Logger logger = org.apache.logging.log4j.LogManager.getLogger(PriorityQueueScheduler.class) ;


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

     private class NamedForkJoinWorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
         private String name;

         private AtomicInteger idx = new AtomicInteger(0);

         public NamedForkJoinWorkerThreadFactory(String name) {
             this.name = name;
         }

         public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
             final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
             worker.setName(name + "-" + idx.incrementAndGet());
             return worker;
         }
     }


    // basic value for system parallelism, eq to (processor_count max 4)
    public final static int SYSTEM_PARALLELISM =
            Runtime.getRuntime().availableProcessors() > 4 ? Runtime.getRuntime().availableProcessors() : 4;

    private ScheduledExecutorService schedulerV2 = Executors.newScheduledThreadPool(SYSTEM_PARALLELISM, new NamedThreadFactory("ms-v2-scheduler"));

    // scheudler for non realtime tasks. Note that here we expect blocking to occur
    private ExecutorService workerExecutorV2 =
              new ForkJoinPool (
                      SYSTEM_PARALLELISM * 4 // we may have long-blocking (i.e. recording file...) tasks here as such we need more threads
                    , new NamedForkJoinWorkerThreadFactory("ms-worker")
                    , null, true
              );

    // where realtime tasks are executed after being in rtScheduler
    private ExecutorService workerExecutorV2RT =
            Executors.newFixedThreadPool(SYSTEM_PARALLELISM * 2, new NamedThreadFactory("ms-v2-rt-worker"));




    private RealTimeScheduler rtpScheduler = new RealTimeScheduler(
            20000000 // 20 ms
            , new OrderedTaskQueue[] { rtpInputQ, rtpMixerQ, rtpOuputQ }
            , workerExecutorV2RT
            , new NamedThreadFactory("ms-rt-rtp")
    );

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
            int remains = queue.getAvailable();
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
    public void submit(Task task, EventQueueType tpe) {
        switch(tpe) {
            case RTP_INPUT :
                rtpInputQ.accept(task);
                break;

            case RTP_OUTPUT :
                rtpOuputQ.accept(task);
                break;

            case RTP_MIXER :
                rtpMixerQ.accept(task);
                break;

            case HEARTBEAT :
                heartbeatQ.accept(task);
                break;

            default:
                submitNonRT(task);
                break;
        }

    }

    /** submits non real-time task. This is executes in worker pool immediately once scheduled here.  **/
    private void submitNonRT(final Task task) {
        workerExecutorV2.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.error("Failed to execute task" + task, t);
                }
            }
        });
    }


    /**
     * Queues task for execution according to its priority.
     *
     * @param task the task to be executed.
     */
    public void submitHeartbeat(Task task) {
        submit(task, EventQueueType.HEARTBEAT);
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
        logger.info("Starting Priority Queue Scheduler started");


        logger.info("Starting HEARTBEAT scheduler");
        schedulerV2.scheduleAtFixedRate(new DrainQueue(heartbeatQ, EventQueueType.HEARTBEAT, workerExecutorV2), 100, 100, TimeUnit.MILLISECONDS);

        logger.info("Starting RTP Queues");

        rtpScheduler.start();


        logger.info("Priority Queue Scheduler started");
    }

    /**
     * Stops scheduler.
     */
    public void stop() {
        logger.info("Shutting down Priority Queue Scheduler");
        schedulerV2.shutdown();
        workerExecutorV2.shutdown();
        rtpScheduler.shutdown();
        workerExecutorV2RT.shutdown();
        logger.info("Shutdown of Priority Queue Scheduler completed");

    }



}
