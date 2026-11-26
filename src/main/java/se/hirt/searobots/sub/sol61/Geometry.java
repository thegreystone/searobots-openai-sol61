package se.hirt.searobots.sub.sol61;

import se.hirt.searobots.api.Vec3;

/** Coordinates are east, north, up; heading is clockwise from north. */
final class Geometry {
    private Geometry() { }

    static double angle(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    static double bearing(Vec3 from, Vec3 to) {
        return Math.atan2(to.x() - from.x(), to.y() - from.y());
    }

    static double clamp(double x, double lo, double hi) {
        return Math.clamp(x, lo, hi);
    }

    /** Positive root of |r + v*t| = speed*t, with a finite fallback. */
    static double interceptTime(Vec3 r, Vec3 v, double speed) {
        double a = v.dot(v) - speed * speed;
        double b = 2 * r.dot(v);
        double c = r.dot(r);
        if (Math.abs(a) < 1e-8) {
            return b < -1e-8 ? -c / b : Math.sqrt(c) / speed;
        }
        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0) return Math.sqrt(c) / speed;
        double d = Math.sqrt(discriminant);
        double t1 = (-b - d) / (2 * a), t2 = (-b + d) / (2 * a);
        double t = t1 > 0 ? t1 : Double.POSITIVE_INFINITY;
        if (t2 > 0) t = Math.min(t, t2);
        return Double.isFinite(t) ? t : Math.sqrt(c) / speed;
    }
}
