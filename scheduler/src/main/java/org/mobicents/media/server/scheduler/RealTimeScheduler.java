package org.mobicents.media.server.scheduler;

import org.apache.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scheduler, that assures to run supplied tasks in real time.
 *
 * It executes tasks within `cycles` where it tries to do best possible job to schedule next invocation of cycle to be
 * exactly `delay` from the last cycle start.
 *
 * It contains array of queues that are drained during each cycle, in their array order.
 * Each task is executed in parallel from the given queue. The next queue is consulted AFTER all tasks confirmed their execution time from the previous queue.
 *
 * Garbage collection
 *
 * Should Garbage collection SoW occur (Stop of the World) this will compensate for missed possibilities to process cycles.
 * For example should SoW take 40 ms, and delay is set 20ms, this will approximatelly run two cycles immediately next to each other,
 * this catching up with any delay occured.
 *
 * We suggest that GC will be tuned to give SoW pause in range of 20-40 ms at max.
 *
 *
 *
 *
 */
public class RealTimeScheduler {

    private static Logger logger =  Logger.getLogger(RealTimeScheduler.class) ;

    // Queues for RTP Tasks, real time 20ms timer
    protected OrderedTaskQueue<RTEventQueueType> rtpInputQ = new OrderedTaskQueue<>();
    protected OrderedTaskQueue<RTEventQueueType> rtpMixerQ = new OrderedTaskQueue<>();
    protected OrderedTaskQueue<RTEventQueueType> rtpOutputQ = new OrderedTaskQueue<>();
    protected OrderedTaskQueue<RTEventQueueType> ss7ReceiverQ = new OrderedTaskQueue<>();
    protected OrderedTaskQueue<RTEventQueueType> dtmfGenQ = new OrderedTaskQueue<>();
    protected OrderedTaskQueue<RTEventQueueType> dtmfInQ = new OrderedTaskQueue<>();

    private long cycleDelay;                        // delay between cycles, computed since start of each cycle, in nanos
    private ExecutorService es;                     // executor service to use to schedule execution of tasks in parallel
    private ScheduledExecutorService scheduler;     // Executor, that schedules next drain cycle to execute all tasks.

    private AtomicBoolean running = new AtomicBoolean(false); // when true scheduler is running

    public RealTimeScheduler(long cycleDelay, ExecutorService es, ScheduledExecutorService scheduler) {
        this.cycleDelay = cycleDelay;
        this.es = es;
        this.scheduler = scheduler;
    }

    /** starts the scheduler by spawning new thread **/
    public void start() {
        if (! running.get()) {
            running.set(true);
            scheduleCycle(System.nanoTime());
        }
    }


    /** shuts down the scheduler. Note this may block up to `delay` **/
    public void shutdown() {
        running.set(false);

    }

    /** schedules task to this queue mixer **/
    public void schedule(Task<RTEventQueueType> task , RTEventQueueType tpe) {
        task.activateTask();
        switch (tpe) {
            case RTP_INPUT: rtpInputQ.accept(task); break;
            case RTP_OUTPUT: rtpOutputQ.accept(task); break;
            case RTP_MIXER: rtpMixerQ.accept(task); break;
            case SS7_RECEIVER: ss7ReceiverQ.accept(task); break;
            case DTMF_GEN: dtmfGenQ.accept(task); break;
            case DTMF_IN: dtmfInQ.accept(task); break;
        }
    }

    /** schedules single cycle **/
    private void scheduleCycle(long start) {
        // start indicates start of cycle. This is increased in every cycle for `cyclicDelay` to possibly
        // compute correctly sleep time if we will finish earlier then next invocation should occur
        // if we are not able to compute everything in this 20ms cycle we will immediately schedule next invocation cycle
        // and as such we try to catchup with any delay occured (i.e. gc pause)


          // perrrom op only when we are running exist otherwise
          if (running.get()) {
              executeCycle(new Runnable() {
                  @Override
                  public void run() {
                      long duration = System.nanoTime() - start;
                      // if we computed before end of cycle we have to sleep until next cycle is ready
                      if (duration < cycleDelay) {
                          // if we catchup with time, sleep until next cycle is ready.
                          long sleepNanos = cycleDelay - duration;
                          scheduler.schedule(new Runnable() {
                              @Override
                              public void run() {
                                  scheduleCycle(start + cycleDelay);
                              }
                          }, sleepNanos, TimeUnit.NANOSECONDS);
                      } else {
                          // compute constant start of the next cycle
                          scheduleCycle(start + cycleDelay);
                          // continue
                      }
                  }
              });
          }

    }

    /**
     * Executes all task in given cycle.
     *
     * Tasks are run in parallel, and when all are completed then this will execute whenDone on the `scheduler`.
     *
     * Note that this will complete immediatelly once all task are scheduled not when all tasks are completed.
     *
     * @param whenDone Consulted when all tasks finished their execustion
     *
     */
    private void executeCycle(final Runnable whenDone)  {
        executeForQueue(this.rtpInputQ, () ->
        executeForQueue(this.rtpMixerQ, () ->
        executeForQueue(this.dtmfInQ, () ->
        executeForQueue(this.dtmfGenQ, () ->
        executeForQueue(this.rtpOutputQ, () ->
        executeForQueue(this.ss7ReceiverQ, () ->
            scheduler.execute(whenDone)
        ))))));
    }

    /**
     * Executes tasks in
     * @param queue
     * @param next
     */
    private void executeForQueue(OrderedTaskQueue<RTEventQueueType>  queue, Runnable next) {
        int togo = queue.getAvailable();
        int count = togo;
        if (count > 0) {
            final AtomicInteger active = new AtomicInteger(count);
            long startAll = System.nanoTime();
            while (togo > 0) {
                final Task task = queue.poll();
                if (task != null) {
                    es.submit(new Runnable() {
                        @Override
                        public void run() {
                            long start = System.nanoTime();
                            try {
                                task.run();
                            } catch (Throwable t) {
                                logger.error("Failed to execute task: " + task, t);
                            }
                            long delay = System.nanoTime() - start;
                            if (delay > 1000000) { // more than one millis
                                logger.warn("Task took to long to complete: " + task.toString() + " took: " + delay + " nanos (" + delay / 1000000 + " millis)");
                            }

                            // decrement counter. If received value is zero, then this was last task to complete and as such
                            // call the `whenDone` callback
                            long remains = active.decrementAndGet();
                            if (remains == 0) {
                                long diff = System.nanoTime() - startAll;
                                if (diff > 20000000L) {
                                    logger.warn("All Tasks took to long to complete: " + (diff / 1000000L) + "ms" + " count: " + count);
                                }

                                // run the callback on the scheduler to avoid SoE
                                next.run();
                            }

                        }
                    });
                }
                togo--;
            }
        } else {
            // nothing to do, just scheduler the next runnable
            next.run();
        }
    }

}
