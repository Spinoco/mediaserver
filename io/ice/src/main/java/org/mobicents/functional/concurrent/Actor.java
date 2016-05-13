package org.mobicents.functional.concurrent;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Processes messages of type `A`, one at a time. Messages are submitted to
 * the actor with the method `send`. Processing is typically performed asynchronously,
 * this is controlled by the provided `strategy`.
 *
 * Memory consistency guarantee: when each message is processed by the `handler`, any memory that it
 * mutates is guaranteed to be visible by the `handler` when it processes the next message, even if
 * the `strategy` runs the invocations of `handler` on separate threads. This is achieved because
 * the `Actor` reads a volatile memory location before entering its event loop, and writes to the same
 * location before suspending.
 *
 * Implementation based on non-intrusive MPSC node-based queue, described by Dmitriy Vyukov:
 * [[http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue]]
 *
 */
public final class Actor<A> {
    // process that much messages on single thread, then force thread switch.
    private static int batchSize = 1024;
    Consumer<A> handler;
    Consumer<Throwable> onError;
    Strategy s;

    private AtomicReference<Node<A>> head = new AtomicReference<>();

    public Actor(Consumer<A> handler, Consumer<Throwable> onError, Strategy s) {
        this.handler = handler;
        this.onError = onError;
        this.s = s;
    }

    /** Pass the message `a` to the mailbox of this actor */
    public void send(A a) {
        Node<A> n = new Node(a);
        Node<A> h = head.getAndSet(n);
        if (h != null) h.lazySet(n);
        else schedule(n);
    }

    private void schedule(Node<A> n) {
        s.run(() -> {act(n); return null;});
    }


    private void act(Node<A> n)  {
        Node<A> cn = n;
        int i = batchSize;
        while(i >= 0) {
            try { handler.accept(cn.a); }
            catch (Throwable t) { onError.accept(t); }

            Node<A> n2 = cn.get();
            if (n2 == null) { scheduleLastTry(cn); break;}
            else if (i == 0) { schedule(n2); } // last in this batch on this thread, schedule other thread to take over
            else { i = i - 1; cn = n2; }
        }
    }

    private void scheduleLastTry(Node<A> n) {
        s.run(() -> { lastTry(n); return null; });
    }

    private void lastTry(Node<A> n) {
        if (! head.compareAndSet(n ,null)) act(next(n));
    }

    private Node<A> next(Node<A> n) {
        Node<A> n2 = n.get();
        while (n2 == null) {
            n2 = n.get();
        }
        return n2;
    }


    private class Node<A> extends AtomicReference<Node<A>> {
        A a;
        Node(A a) { this.a = a; }
    }




}
