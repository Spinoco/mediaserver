package org.mobicents.media.server.scheduler;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A scheduler, that assures to run supplied tasks in real time.
 *
 * It executes tasks within `cycles` where it tries to do best possible job to schedule next invocation of cycle to be
 * exactly `delay` from the last cycle start.
 *
 * It takes array of queues that are drained during each cycle, in their array order.
 * Each task is executed in parallel from the given queue. The next queue is consulted AFTER all tasks confirmed their execution time from the previous queue.
 *
 * Garbage collection
 *
 * Should Garbage collection SoW occur (Stop of the world) this will compensate for missed possibilities to process cycles.
 * For example shoudl SoW take 40 ms, and delay is set 20ms, this will aproximatelly run two cycles immediatelly next to each other.
 *
 *
 *
 *
 */
public class RealTimeScheduler {

    private static Logger logger =  org.apache.logging.log4j.LogManager.getLogger(RealTimeScheduler.class) ;

    private long cycleDelay;                    // delay between cycles, computed since start of each cycle, in nanos
    private OrderedTaskQueue[] queues;          // available queues to process in each cycle
    private ExecutorService es;                 // executor service to use to schedule execution of tasks in parallel
    private ThreadFactory tf;                   // thread factory used to construct scheduling thread of this scheduler

    private AtomicBoolean running = new AtomicBoolean(false); // when true scheduler is running
    private CountDownLatch done = new CountDownLatch(1);



    public RealTimeScheduler(long cycleDelay, OrderedTaskQueue[] queues, ExecutorService es, ThreadFactory tf) {
        this.cycleDelay = cycleDelay;
        this.queues = queues;
        this.es = es;
        this.tf = tf;
    }

    /** starts the scheduler by spawning new thread **/
    public void start() {
        if (! running.get()) {
            running.set(true);
            tf.newThread(new Runnable() {
                @Override
                public void run() {
                    // noted start of cycle. This is increased in every cycle for `cyclicDelay` to possibly
                    // compute correctly sleep time if we will finish earlier then next invocation should occur
                    // if we are not able to compute everything in this 20ms cycle we will immediately schedule next invocation cycle
                    // and as such we try to catchup with any delay occured (i.e. gc pause)
                    long cycleStart = System.nanoTime();
                    try {
                        while (running.get()) {
                            executeCycle();
                            long duration = System.nanoTime() - cycleStart;
                            if (duration < cycleDelay) {
                                // if we catchup with time, sleep until next cycle is ready.
                                long sleepNanos = cycleDelay - duration;
                                Thread.sleep(sleepNanos/1000000, (int)(sleepNanos % 1000000));

                            }
                            // compute constant start of the next cycle
                            cycleStart = cycleStart + cycleDelay;
                        }

                    } catch (InterruptedException e) {
                        logger.error("Real time scheduler interrupted while sleeping", e);
                        running.set(false);
                    }

                    done.countDown();

                }
            }).start();
        }
    }


    /** shuts down the scheduler. Note this may block up to `delay` **/
    public void shutdown() {
        running.set(false);
        try {
            done.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Executes all task in given cycle
     *
     * This awaits for all tasks to signal their completion before advancing to next queue and then next cycle.
     *
     *
     */
    private void executeCycle() throws InterruptedException {

        for ( OrderedTaskQueue queue: this.queues)  {
            int togo = queue.getAvailable();
            int count = togo;
            final CountDownLatch taskCompletion = new CountDownLatch(togo);
            long startAll = System.nanoTime();
            while(togo > 0) {
                final Task task = queue.poll();
                if (task != null) {
                    es.submit(new Runnable() {
                        @Override
                        public void run() {
                            long start = System.nanoTime();
                            try {
                                task.run();
                            } catch (Throwable t) {
                                logger.error("Failed to execute task: " + task , t);
                            } finally {
                                taskCompletion.countDown();
                            }
                            long delay = System.nanoTime() - start;
                            if (delay > 1000000) { // more than one millis
                                logger.warn("Task took to long to complete: " + task.toString() + " took: " + delay + " nanos (" + delay / 1000000 + " millis)");
                            }
                        }
                    });
                }
                togo --;
            }
            taskCompletion.await();
            long diff = System.nanoTime() - startAll;
            if (diff > 20000000L) {
                logger.warn("All Tasks took to long to complete: " + (diff/1000000L) + "ms" + " count: " + count);
            }
        }
    }


}
