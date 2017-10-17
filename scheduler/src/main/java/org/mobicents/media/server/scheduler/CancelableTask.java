package org.mobicents.media.server.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created with IntelliJ IDEA.
 * User: raulim
 * Date: 17.10.17
 */
public abstract class CancelableTask extends Task {

    private AtomicBoolean active;

    public CancelableTask(AtomicBoolean active) {
        this.active = active;
    }

    @Override
    public void run() {
        if (active.get()) {
            super.run();
        }
    }
}
