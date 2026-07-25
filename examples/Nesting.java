// Companion to OrderService.java, covering the declaration shapes whose names are easy to get
// wrong: nested types, an enum with methods, and a record. Every method here must come back with
// its full dotted path.
package com.example.modern;

public class Widget {

    public int spin(String name) {
        return name.length();
    }

    public static class Cog {
        public int turn(int times) {
            return times;
        }
    }

    public enum Mode {
        FAST;

        public String label() {
            return name();
        }
    }
}

record Circle(double radius) {
    public double area() {
        return Math.PI * radius * radius;
    }
}
