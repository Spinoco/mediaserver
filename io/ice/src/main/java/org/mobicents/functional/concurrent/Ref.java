package org.mobicents.functional.concurrent;

/**
 * Asynchronous reference.
 *
 * Useful for defining other asynchronous primitives like for example semaphore, signal, etc.
 */
public interface Ref<A> {



    static<A> Task<Ref<A>> apply(Strategy S) {
//        return (Task.delay( () -> {
//
//
//
//        }));

        return null;
    }

    interface Msg<A> {}




    class RefImpl<A> implements Ref<A> {

    }


}
