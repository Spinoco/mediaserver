package org.mobicents.functional.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Only private use for the Task
 */
interface Future<A> {

    /**
     * Produce Task<B>  by supplying function a -> Task<B>
     */
    <B> Future<B> flatMap(Function<A,Future<B>> f);

    /**
     * Produce Task<B>  by supplying function a -> b
     */
    default <B> Future<B> map(Function<A,B> f) { return(flatMap(a -> now(f.apply(a)))); }


    default void runAsync(Consumer<A> cb) {
        Future.listen(this,a -> { cb.accept(a); return Trampoline.done(null); });
    }

    default A run() throws Throwable {
        if (this instanceof Now) return(((Now<A>) this).a);
        else {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<A> result = new AtomicReference<A>(null);
            runAsync( a -> { result.set(a); latch.countDown(); });
            latch.await();
            return(result.get());
        }
    }


    static<A> Future<A> now(A a) { return new Now<>(a); }
    static<A> Future<A> delay(Supplier<A> thunk) { return new Suspend<>(() -> now(thunk.get())); }
    static<A> Future<A> suspend(Supplier<Future<A>> thunk) { return new Suspend<>(thunk); }
    static<A> Future<A> async(Strategy s, Consumer<Consumer<A>> listen) {
      return(new Async<>(cb -> listen.accept( a -> {s.run( () -> { cb.apply(a).run() ; return null; }); })));
    }

    /**
     * Strip any suspended computations, to leave only Now or Async computations
     */
    static<A> Future<A> step(Future<A> t) {
        Future<Object> c = (Future<Object>) t;
        Future<A> r = null;
        while(r == null) {
            if(c instanceof Suspend) c = ((Suspend<Object>) c).thunk.get();
            else if (c instanceof BindSuspend) {
                BindSuspend<Object,Object> bs = ((BindSuspend<Object,Object>) c);
                c = bs.thunk.get().flatMap(bs.f);
            } else {
                r = ((Future<A>) c);
            }
        }
        return r;
    }

    static<A> void listen(Future<A> t, Function<A, Trampoline<Void>> f) {
        Future<A> stepped = step(t);
        if (stepped instanceof Now) f.apply(((Now<A>) stepped).a).run();
        else if (stepped instanceof Async) ((Async<A>) stepped).register.accept(f);
        else if (stepped instanceof BindAsync) {
            BindAsync<Object,A> bind = (BindAsync<Object,A>) stepped;
            bind.register.accept( o -> {
                return(Trampoline.delay(() -> bind.f.apply(o)).map(ta -> { listen(ta,f); return ((Void)null); }));
            });
        } else {
            // impossible
        }
    }


    /////////////////////////////////////////////////////////////
    //
    // Algebra
    //
    /////////////////////////////////////////////////////////////
    class Now<A> implements Future<A> {
        A a;
        Now(A a) { this.a = a; }


        @Override
        public <B> Future<B> flatMap(Function<A, Future<B>> f) {
           return f.apply(a);
        }


    }

    class Async<A> implements Future<A> {
        Consumer<Function<A,Trampoline<Void>>> register;
        Async(Consumer<Function<A,Trampoline<Void>>> register) { this.register = register ;}

        @Override
        public <B> Future<B> flatMap(Function<A, Future<B>> f) {
            return (new BindAsync<>(register, f));
        }

    }

    class Suspend<A> implements Future<A> {
        Supplier<Future<A>> thunk;
        Suspend(Supplier<Future<A>> thunk) { this.thunk = thunk;}

        @Override
        public <B> Future<B> flatMap(Function<A, Future<B>> f) {
            return (new BindSuspend<>(thunk, f));
        }

    }

    class BindSuspend<A,B> implements Future<B> {
        Supplier<Future<A>> thunk;
        Function<A, Future<B>> f ;
        BindSuspend(Supplier<Future<A>> thunk,   Function<A, Future<B>> f) {
            this.thunk = thunk;
            this.f = f;
        }

        @Override
        public <B1> Future<B1> flatMap(Function<B, Future<B1>> f0) {
            return new Suspend<>(() ->  new BindSuspend<>(thunk, f.andThen(tb -> tb.flatMap(f0)) ));
        }

    }

    class BindAsync<A,B> implements Future<B> {
        Consumer<Function<A,Trampoline<Void>>> register;
        Function<A, Future<B>> f ;
        BindAsync(  Consumer<Function<A,Trampoline<Void>>> register, Function<A, Future<B>> f) {
            this.register = register;
            this.f = f;
        }

        @Override
        public <B1> Future<B1> flatMap(Function<B, Future<B1>> f0) {
            return new Suspend<>(() ->  new BindAsync<>(register, f.andThen(tb -> tb.flatMap(f0)) ));
        }

    }

}
