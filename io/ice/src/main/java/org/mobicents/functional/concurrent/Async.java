package org.mobicents.functional.concurrent;


import org.mobicents.functional.Option;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Asynchronous utilities for Task. Shall be used as main user API entry point besides the `Task` functions.
 */
public interface Async {

    /** create suspended computation evaluated lazily **/
    <A> Task<A> suspend(Supplier<A> f);

    /** Create an asynchronous, concurrent mutable reference. */
    <A> Task<Ref<A>> ref();

    /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
    <A> Task<Ref<A>> refOf(A a);

    /** Obtain the value of the `Ref`, or wait until it has been `set`. */
    <A> Task<A> get(Ref<A> ref);

    /**
     * Try modifying the reference once, returning `None` if another
     * concurrent `set` or `modify` completes between the time
     * the variable is read and the time it is set.
     */
    <A> Task<Option<Change<A>>> tryModify(Ref<A> ref, Function<A,A> f);

    /** Repeatedly invoke `[[tryModify]](f)` until it succeeds. */
    <A> Task<Change<A>> modify (Ref<A> ref, Function<A,A> f);

    /**
     * Asynchronously set a reference. After the returned `F[Unit]` is bound,
     * the task is running in the background. Multiple tasks may be added to a
     * `Ref[A]`.
     *
     * Satisfies: `set(r)(t) flatMap { _ => get(r) } == t`.
     */
    <A> Task<Void> set(Ref<A> ref, Task<A> ta);

    /** Variant of `set` taking pure `a` as argument **/
    <A> Task<Void> setPure(Ref<A> ref, A a);




    /** Class indicating change to Reference **/
    class Change<A> {
        A previous;
        A now;
        Change(A previous,  A now) {
            this.previous = previous;
            this.now = now;
        }

        public A getPrevious() {
            return previous;
        }

        public A getNow() {
            return now;
        }
    }


    static Async apply(Strategy s) {
        throw new RuntimeException("todo"); //todo
    }

}
