package org.mobicents.functional.concurrent;


import java.util.function.Function;
import java.util.function.Supplier;

public interface Trampoline<A> {

    default<B> Trampoline<B> flatMap(Function<A, Trampoline<B>> f) { return new Bind(this,f);  }
    default<B> Trampoline<B> map(Function<A, B> f) { return (this.flatMap(f.andThen(Trampoline::done))); }
    default A run() { return Trampoline.run(this); }

    class Done<A> implements Trampoline<A> {
       private A a;
       Done(A a) { this.a = a; }
    }

    class Suspend<A> implements Trampoline<A> {
       private Supplier<A> thunk;
       Suspend(Supplier<A> thunk) { this.thunk = thunk; }
    }

    class Bind<A,B> implements Trampoline<B> {
        private Trampoline<A> sub;
        private Function<A,Trampoline<B>> f;
        Bind(Trampoline<A> sub, Function<A,Trampoline<B>> f) {
            this.sub = sub;
            this.f = f;
        }
    }


    static<A> Trampoline<A> done(A a) { return new Done(a); }
    static<A> Trampoline<A> delay(Supplier<A> thunk) { return new Suspend(thunk); }
    static<A> Trampoline<A> suspend(Supplier<Trampoline<A>> thunk) {
        return (new Suspend<Void>(() -> null).flatMap((v) -> thunk.get()));
    }


    static<A> A run(Trampoline<A> t) {
        Trampoline<Object> c = (Trampoline<Object>)t;
        A a = null;

        while (a == null)  {
           if (c instanceof Done) a = ((Done<A>) c).a;
           else if (c instanceof Suspend) a = ((Suspend<A>) c).thunk.get();
           else {
               Bind bind =  ((Bind) c);
               if (bind.sub instanceof Done) { c = (Trampoline)bind.f.apply(((Done) bind.sub).a); }
               else if (bind.sub instanceof Suspend) { c = (Trampoline)bind.f.apply(((Suspend) bind.sub).thunk.get());  }
               else {
                   Bind bind2 = ((Bind) bind.sub);
                   c = bind2.sub.flatMap(x -> ((Trampoline) bind2.f.apply(x)).flatMap(y -> ((Trampoline) bind.f.apply(y))));
               }
           }
        }

        return a;
    }


}
