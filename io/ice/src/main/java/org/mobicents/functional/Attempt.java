package org.mobicents.functional;


import java.util.function.Function;
import java.util.function.Supplier;

public interface Attempt<A> {

    <B> B fold(Function<A,B> success, Function<Throwable, B> failed);
    <B> Attempt<B> map(Function<A,B> f);
    A get() throws Throwable ;


    class Failure<A> implements Attempt<A> {
        Throwable err;
        Failure(Throwable err) { this.err = err; }

        @Override
        public <B> B fold(Function<A, B> success, Function<Throwable, B> failed) {
            return failed.apply(err);
        }

        @Override
        public A get() throws Throwable {
            throw err;
        }

        @Override
        public <B> Attempt<B> map(Function<A, B> f) {
            return ((Attempt<B>) this);
        }
    }

    class Success<A> implements Attempt<A> {
        A a;
        Success(A a) { this.a = a; }

        @Override
        public <B> B fold(Function<A, B> success, Function<Throwable, B> failed) {
            return success.apply(a);
        }

        @Override
        public A get() throws Throwable {
            return (a);
        }

        @Override
        public <B> Attempt<B> map(Function<A, B> f) {
            return(new Success(f.apply(a)));
        }
    }


    static<A> Attempt<A> success(A a) { return new Success<>(a); }
    static<A> Attempt<A> failed(Throwable err) { return new Failure<>(err); }
    static<A> Attempt<A> attempt(Supplier<A> f) {
        try {  return(success(f.get())); }
        catch (Throwable t) { return failed(t); }
    }

}
