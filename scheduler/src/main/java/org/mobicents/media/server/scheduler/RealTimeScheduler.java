package org.mobicents.media.server.scheduler;

import org.apache.log4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by pach on 10/10/17.
 */
public class RealTimeScheduler {

    private static Logger logger = Logger.getLogger(RealTimeScheduler.class) ;

    final private SchedulingQueue[] queues;

    // current time of next 1ms invocation.
    // this is used to indicate next 1ms bucket that will be executed.
    private AtomicLong next = new AtomicLong(0);

    // If true, this scheduler is running.
    private AtomicBoolean running = new AtomicBoolean(false);


    // maximum delay to support. Tasks with greater delay are always rounded to this maximum
    private int maxDelayMs;

    // pool to use for scheduling task work
    private ExecutorService pool;

    // index of the scheduling thread;
    private AtomicInteger threadIndex = new AtomicInteger(0);



    public RealTimeScheduler(int maxDelayMs, ExecutorService pool) {
        this.maxDelayMs = maxDelayMs;
        this.pool = pool;
        this.queues = new SchedulingQueue[maxDelayMs + 1];
        for ( int i = 0; i <= maxDelayMs; i++ ) {
            this.queues[i] = new SchedulingQueue();
        }

    }

    /**
     * Schedules task with millisecond precision. Supplied nanos are converted to millis and scheduled approximately
     * at given ms. The millis are rounded to next greater millisecond (CEIL)
     * @param task              Task to schedule
     * @param delayNanos        Delay, in nanos
     */
    public void schedule(Task task, long delayNanos) {
        if (delayNanos > 0) {
            int millis = (int) (delayNanos / 1000000);
            int offset = (millis > maxDelayMs) ? maxDelayMs : millis;
            boolean scheduled = false;

            while (!scheduled) {
                scheduled = scheduleToQueue(task, offset);
            }


        } else {
            this.pool.submit(task);
        }
    }

    /** start this scheduler **/
    public void start() {
       if (! this.running.getAndSet(true)) {
          this.next.set(System.currentTimeMillis());

          Thread t = new Thread(()->{
              while (this.running.get()) {
                long next = cycle();
                long delay = next - System.nanoTime();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay / 1000000, (int)(delay % 1000000));
                    } catch (InterruptedException e) {
                        logger.error("Scheduler thread interrupted", e);
                        this.running.set(false);
                    }
                }

              }
          });

          t.setName("ms-rt-scheduler-" + threadIndex.incrementAndGet());
          t.start();

       }

    }

    /** stops this scheduler, tasks are not drained **/
    public void shutdown() {
        this.running.set(false);
    }

    /** schedule task to queue based on the offset that indicates bucket as delay in ms from now. Yields true, if the schedule was successfull **/
    private boolean scheduleToQueue(Task task, int offset) {
        SchedulingQueue queue = queues[(int)((next.get() + offset) % queues.length)];
        return queue.offer(task);
    }

    /** Executes one cycle of the task (one bucket). returns time of the desired next invocation in nanos**/
    private long cycle() {
        // shift task to use next bucket while we processing this one
        long current = next.getAndUpdate(time -> time + 1);
        SchedulingQueue queue = queues[(int)(current % queues.length)];
        int count = queue.drain();
        while (count < 0) {
            Task t = queue.poll();
            if (t != null) { pool.submit(t); }
            count --;
        }

        // recycle queue so next tasks can get in
        queue.recycle();
        return (current + 1) * 1000000;
    }


    class SchedulingQueue {

        private AtomicInteger count = new AtomicInteger(0);

        private ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<Task>();


        /** offers the task if the queue is not closed. Yields false, if queue is closed, that means no new tasks has to be scheduled **/
        public boolean offer(Task task) {
            int current = count.incrementAndGet();
            if (count.incrementAndGet() > 0) {
                queue.offer(task);
                return true;
            } else {
                count.compareAndSet(current, Integer.MIN_VALUE);
                return false;
            }
        }

        /** gets the next task in queue **/
        private Task poll() {
            return queue.poll();
        }

        /** causes to drain queue, returning number of elements that has to be taken from the Q **/
        private int drain() {
            return count.getAndSet(Integer.MIN_VALUE);
        }

        /** recycles the queue by setting the count of elements back to 0 **/
        private void recycle() {
            count.set(0);
        }


    }
}
