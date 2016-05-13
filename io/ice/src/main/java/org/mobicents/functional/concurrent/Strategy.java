package org.mobicents.functional.concurrent;

import java.util.function.Supplier;

/**
 * Created by pach on 12/05/16.
 */
public interface Strategy {
    void run(Supplier<Void> thunk);

    static Strategy fromFixedThreadPool(int maxThreads, String threadName) {
        // todo implement
        return null;
    }

}


