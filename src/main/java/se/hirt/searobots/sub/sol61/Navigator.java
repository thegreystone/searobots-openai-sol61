package se.hirt.searobots.sub.sol61;

import java.util.List;
import se.hirt.searobots.api.*;

/** Global terrain corridors plus short-horizon hull and depth clearance. */
final class Navigator {
    private final TerrainMap terrain;
    private final BattleArea area;
    private final PathPlanner planner;
    private List<PathPlanner.CorridorPoint> route = List.of();
    private Vec3 lastGoal = new Vec3(1e9, 1e9, 0);
    private long planned = -100000;
    private int leg;
    private double safeHeading, safeDepth;
    private double stuckSeconds;
    private long reverseUntil;

    Navigator(MatchContext context) {
        terrain = context.terrain();
        area = context.config().battleArea();
        planner = new PathPlanner(terrain, -95, 200, 100, 350, 4);
    }

    Vec3 constrain(Vec3 p) {
        double x = p.x(), y = p.y();
        if (area instanceof BattleArea.Circular c) {
            double r = Math.hypot(x, y), limit = Math.max(100, c.radius() - 800);
            if (r > limit) { x *= limit / r; y *= limit / r; }
        } else if (area instanceof BattleArea.Rectangular r) {
            x = Geometry.clamp(x, -r.halfWidth() + 800, r.halfWidth() - 800);
            y = Geometry.clamp(y, -r.halfHeight() + 800, r.halfHeight() - 800);
        }
        return new Vec3(x, y, p.z());
    }

    void steer(SubmarineInput in, SubmarineOutput out, Vec3 requestedGoal, double speed, Purpose purpose) {
        Pose pose = in.self().pose();
        Vec3 p = pose.position(), goal = constrain(requestedGoal);
        if (in.tick() - planned > 1250 || goal.horizontalDistanceTo(lastGoal) > 650) {
            route = planner.findCorridor(p.x(), p.y(), goal.x(), goal.y());
            lastGoal = goal;
            planned = in.tick();
            leg = route.size() > 1 ? 1 : 0;
        }
        Vec3 aim = goal;
        while (leg < route.size()) {
            var next = route.get(leg);
            aim = new Vec3(next.x(), next.y(), goal.z());
            if (p.horizontalDistanceTo(aim) > 240) break;
            leg++;
        }
        if (leg >= route.size()) aim = goal;
        if (purpose == Purpose.EVADE || purpose == Purpose.RALLY) aim = goal;
        double desired = Geometry.bearing(p, aim);
        if (area.distanceToBoundary(p.x(), p.y()) < 650) desired = Math.atan2(-p.x(), -p.y());

        // Recompute only at 5 Hz; hold the chosen course between decisions.
        if (in.tick() % 10 == 0) {
            double bestScore = Double.POSITIVE_INFINITY;
            safeHeading = desired;
            for (int k = -10; k <= 10; k++) {
                double h = desired + k * Math.PI / 10;
                double score = Math.abs(k) * 0.18 + Math.abs(Geometry.angle(h - pose.heading())) * 0.08;
                double x = p.x(), y = p.y(), course = pose.heading();
                double yaw = in.self().velocity().angular().z();
                double projectedSpeed = Math.max(5, Math.min(speed, Math.max(7, in.self().surgeSpeed())));
                for (double seconds = 5; seconds <= 75; seconds += 5) {
                    double command = Geometry.clamp(1.8 * Geometry.angle(h - course) - 22 * yaw, -0.55, 0.55);
                    yaw += (command * 0.13 - yaw) * (1 - Math.exp(-5.0 / 25));
                    course += yaw * 5;
                    x += Math.sin(course) * projectedSpeed * 5;
                    y += Math.cos(course) * projectedSpeed * 5;
                    double floor = floorNear(x, y, 45);
                    if (floor > -95) score += 20 + (floor + 95) * 0.5;
                    if (area.distanceToBoundary(x, y) < 350) score += 50;
                    double climb = floor + 45 - p.z();
                    double available = seconds * 1.6;
                    if (climb > available) score += (climb - available) * 0.05;
                }
                if (score < bestScore) { bestScore = score; safeHeading = h; }
            }
            double highest = floorNear(p.x(), p.y(), 55);
            for (double d = 100; d <= 1100; d += 100) {
                double h = pose.heading() + Geometry.clamp(Geometry.angle(safeHeading - pose.heading()), -0.5, 0.5) * Math.min(1, d / 500);
                highest = Math.max(highest, floorNear(p.x() + Math.sin(h) * d, p.y() + Math.cos(h) * d, 50));
            }
            safeDepth = Math.min(-28, Math.max(goal.z(), highest + 65));
        }
        double error = Geometry.angle(safeHeading - pose.heading());
        out.setRudder(Geometry.clamp(1.8 * error - 22 * in.self().velocity().angular().z(), -0.55, 0.55));
        double dz = safeDepth - p.z();
        double desiredPitch = Geometry.clamp(dz / 450, -0.12, 0.14);
        out.setSternPlanes(Geometry.clamp(3.5 * desiredPitch + 2 * (desiredPitch - pose.pitch())
                - 12 * in.self().velocity().angular().y(), -0.55, 0.55));
        out.setBallast(Geometry.clamp(0.5 + dz * 0.008 - in.self().velocity().linear().z() * 0.3, 0, 1));
        double gap = p.z() - floorNear(p.x(), p.y(), 55);
        if (gap < 35) speed = Math.min(speed, 4);
        if (Math.abs(error) > 1.0 && purpose != Purpose.EVADE && purpose != Purpose.RALLY) speed = Math.min(speed, 7);
        double health = (double) in.self().hp() / 1000;
        double thrustFactor = 0.3 + 0.7 * health * health;
        out.setEngineClutch(true);
        out.setThrottle(Geometry.clamp(speed * speed / (225 * thrustFactor)
                + (speed - in.self().surgeSpeed()) * 0.10, -0.5, 1));
        if (Math.abs(in.self().surgeSpeed()) < 0.7 && gap < 50 && in.tick() > 1000) {
            stuckSeconds += in.deltaTimeSeconds();
        } else stuckSeconds = 0;
        if (stuckSeconds > 8 && in.tick() >= reverseUntil) {
            reverseUntil = in.tick() + 2000;
            planned = -100000;
            stuckSeconds = 0;
        }
        if (in.tick() < reverseUntil) {
            out.setThrottle(-1);
            out.setRudder(0.4);
            out.setSternPlanes(0);
            out.setBallast(0.5);
            out.setStatus("BACK CLEAR");
        }
        if (in.tick() % 10 == 0) {
            out.publishWaypoint(new Waypoint(aim.x(), aim.y(), safeDepth, true));
            out.publishStrategicWaypoint(new Waypoint(goal.x(), goal.y(), safeDepth, true), purpose);
        }
    }

    private double floorNear(double x, double y, double r) {
        return Math.max(terrain.elevationAt(x, y), Math.max(
                Math.max(terrain.elevationAt(x + r, y), terrain.elevationAt(x - r, y)),
                Math.max(terrain.elevationAt(x, y + r), terrain.elevationAt(x, y - r))));
    }
}
