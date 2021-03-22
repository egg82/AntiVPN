package me.egg82.antivpn.core;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * This class should be used sparingly, only when needed.
 * It's far too easy to abuse it and have it become an anti-pattern.
 * Also, hashCode is calculated on Pair creation, so any changes made to objects
 * in this Pair won't be reflected in the hashCode.
 */
public class Pair<T1, T2> {
    private final @NotNull T1 t1;
    private final @NotNull T2 t2;

    private final int hc;

    public Pair(@NotNull T1 t1, @NotNull T2 t2) {
        this.t1 = t1;
        this.t2 = t2;

        this.hc = Objects.hash(t1, t2);
    }

    public @NotNull T1 getT1() { return t1; }

    public @NotNull T2 getT2() { return t2; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> pair = (Pair<?, ?>) o;
        return Objects.equals(t1, pair.t1) &&
                Objects.equals(t2, pair.t2);
    }

    @Override
    public int hashCode() { return hc; }

    @Override
    public String toString() {
        return "Pair{" +
                "t1=" + t1 +
                ", t2=" + t2 +
                '}';
    }
}
