package org.mobicents.functional;

/**
 * Disjunction type that can be Either Left or Right
 */
public interface Either<A,B> {

    class Left<A,B> implements Either<A,B> {
        A a;
        Left(A a) { this.a = a;}
    }

    class Right<A,B> implements Either<A,B> {
        B b;
        Right(B b) { this.b = b;}
    }


    static<A,B> Either<A,B> left(A a) { return new Left(a); }
    static<A,B> Either<A,B> right(B b) { return new Right(b); }
}
