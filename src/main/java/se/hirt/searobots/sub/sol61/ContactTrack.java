package se.hirt.searobots.sub.sol61;

import se.hirt.searobots.api.SonarContact;
import se.hirt.searobots.api.Vec3;

/** A bounded alpha-beta filter. Passive fixes never displace a recent active fix. */
final class ContactTrack {
    Vec3 position = Vec3.ZERO;
    Vec3 velocity = Vec3.ZERO;
    double age = 1e9, activeAge = 1e9, uncertainty = 10000;
    boolean valid;
    private double time, lastObservation = -1e9;

    void advance(double dt) {
        time += dt;
        age += dt;
        activeAge += dt;
        position = position.add(velocity.scale(dt));
        uncertainty += dt * 2;
    }

    void seed(Vec3 p, Vec3 v) {
        position = p;
        velocity = v;
        valid = true;
        age = activeAge = 0;
        uncertainty = 120;
    }

    void observe(Vec3 observer, SonarContact c, boolean terminal) {
        if (!(c.range() > 0) || !Double.isFinite(c.range()) || !Double.isFinite(c.bearing())) return;
        boolean active = c.isActive();
        if (!active && (activeAge < 12 || time - lastObservation < 0.5)) return;
        double z = Double.isFinite(c.estimatedDepth()) ? c.estimatedDepth()
                : valid ? position.z() : observer.z();
        z = Geometry.clamp(z, -600, -8);
        double dz = z - observer.z();
        double horizontal = Math.sqrt(Math.max(c.range() * c.range() - dz * dz, c.range() * c.range() * 0.1));
        Vec3 measurement = new Vec3(observer.x() + horizontal * Math.sin(c.bearing()),
                observer.y() + horizontal * Math.cos(c.bearing()), z);
        double gap = time - lastObservation;
        double alpha = active ? (terminal ? 0.65 : 0.55) : 0.08;
        if (!valid || age > 90) {
            position = measurement;
            velocity = Vec3.ZERO;
            valid = true;
        } else {
            Vec3 residual = measurement.subtract(position);
            position = position.add(new Vec3(residual.x() * alpha, residual.y() * alpha,
                    residual.z() * (active ? (terminal ? 0.5 : 0.25) : 0)));
            if (active && gap > 0.4 && gap < 30) {
                double beta = terminal ? 0.16 : 0.10;
                velocity = velocity.add(new Vec3(residual.x(), residual.y(), 0).scale(beta / gap));
            }
        }
        if (Double.isFinite(c.estimatedHeading()) && c.estimatedSpeed() >= 0 && c.solutionQuality() > 0.35) {
            double speed = Geometry.clamp(c.estimatedSpeed(), 0, 16);
            Vec3 reported = new Vec3(Math.sin(c.estimatedHeading()) * speed,
                    Math.cos(c.estimatedHeading()) * speed, 0);
            velocity = velocity.scale(0.85).add(reported.scale(0.15));
        }
        if (velocity.length() > 17) velocity = velocity.scale(17 / velocity.length());
        lastObservation = time;
        age = 0;
        if (active) activeAge = 0;
        uncertainty = Math.max(10, Math.max(c.rangeUncertainty(), horizontal * c.bearingUncertainty()));
    }
}
