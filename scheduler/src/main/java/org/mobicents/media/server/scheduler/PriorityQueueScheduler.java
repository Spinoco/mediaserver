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


    private static Logger logger = Logger.getLogger(PriorityQueueScheduler.class) ;


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
            worker.setName(name+ "-" + idx.incrementAndGet());
            return worker;
        }
    }



    // Scheduler for heartbeats, that are scheduled each 100 mills
    private ScheduledExecutorService heartBeatScheduler =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2, new NamedThreadFactory("ms-heartbeat"));

    // rt scheduler that schedules tasks, that need to be run in 20ms metronome
    // so the scheduled tasks here are scheduled from 1ns until 20ms
    private ScheduledExecutorService rtScheduler =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2, new NamedThreadFactory("ms-rt-scheduler"));

    // where realtime tasks are executed after being in rtScheduler
    private ExecutorService rtWorkerExecutor =
              new ForkJoinPool (
          Runtime.getRuntime().availableProcessors() * 4
                    , new NamedForkJoinWorkerThreadFactory("ms-rt-worker")
                    , null, true
              );

    // scheudler for non realtime tasks. Note that here we expect blocking to occur
    private ExecutorService workerExecutor =
            new ForkJoinPool (
                    Runtime.getRuntime().availableProcessors() * 4
                    , new NamedForkJoinWorkerThreadFactory("ms-worker")
                    , null, true
            );



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
    public void submit(Task task) {
        workerExecutor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Failed to execute task" + task, t);
            }
        });

    }


    /**
     * Queues task for execution according to its priority.
     *
     * @param task the task to be executed.
     */
    public void submitHeartbeat(Task task) {
        heartBeatScheduler.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Failed to execute heartbeat " + task, t);
            }
        }, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Queues chain of the tasks for execution.
     *
     */
    public void submit(TaskChain taskChain) {    	
        taskChain.start();
    }


    /**
     * Submits task to be executed in RealTime Q.
     * If the task is > 0 nanos, the task is delayed for supplied value, otherwise it is executed instantly.
     * @param task
     * @param nanoDelay
     */
    public void submitRT(Task task, long nanoDelay) {
        if (nanoDelay <= 0) {
            scheduleRTTaskNow(task);
        } else {
            rtScheduler.schedule(() -> {
               scheduleRTTaskNow(task);
            }, nanoDelay/1000000L, TimeUnit.MILLISECONDS);
        }

    }

    /** schedules immediate execution of real-time task **/
    private void scheduleRTTaskNow(Task task) {
        rtWorkerExecutor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Failed to execute runtime task" + task, t);
            }
        });
    }
    
    /**
     * Starts scheduler.
     */
    public void start() {

        if (clock == null) {
            throw new IllegalStateException("Clock is not set");
        }

        logger.info("Priority Queue Scheduler started");
    }

    /**
     * Stops scheduler.
     */
    public void stop() {
        logger.info("Shutting down Priority Queue Scheduler");
        heartBeatScheduler.shutdown();
        rtScheduler.shutdown();
        workerExecutor.shutdown();
        rtWorkerExecutor.shutdown();
        logger.info("Shutdown of Priority Queue Scheduler completed");

    }



}
