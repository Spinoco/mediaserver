package org.mobicents.media.server.scheduler;

/**
 *  Task, that allows to be scheduled by metronome
 */

import java.util.concurrent.atomic.AtomicLong;

public abstract class MetronomeTask extends Task {

    private PriorityQueueScheduler scheduler;
    private long metronomeDelay;
    private AtomicLong metronome = new AtomicLong(System.nanoTime());

    public MetronomeTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
        this.scheduler = scheduler;
        this.metronomeDelay = metronomeDelay;
    }

    /**
     * Some scenarios requires reinitialization of metronome, for this purpose this can be used.
     */
    public void reinit() {
        this.metronome.set(System.nanoTime());
    }

    /**
     * Schedules next invocation of this task
     * that is compute the next real time when the task shall be run, and insert any delay needed, if any.
     * The scheduler is responsible for resolving times <= 0 correctly to reschedule task again
     *
     */
    protected void next() {
        long next = metronome.get() + metronomeDelay;
        metronome.set(next);
        long delay = next - System.nanoTime();

        scheduler.submitRT(this, delay);
    }


}
