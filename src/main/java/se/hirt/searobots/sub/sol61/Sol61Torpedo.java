package se.hirt.searobots.sub.sol61;

import java.util.List;
import se.hirt.searobots.api.*;
import se.hirt.searobots.api.SonarContact.Classification;

/** Independent seeker, using only mission data and the torpedo's own sonar. */
public final class Sol61Torpedo implements TorpedoController {
    private ContactTrack target;
    private TerrainMap terrain;
    private Vec3 launch, owner, ownerVelocity;
    private double time;
    private boolean hasMission;
    private PathPlanner planner;
    private List<PathPlanner.CorridorPoint> route = List.of();
    private int leg;
    private double planned;
    private Vec3 routeGoal = Vec3.ZERO;

    public Sol61Torpedo() { }

    static String mission(Vec3 target, Vec3 velocity, Vec3 owner, Vec3 ownerVelocity) {
        return "S61|" + target.x() + "|" + target.y() + "|" + target.z()
                + "|" + velocity.x() + "|" + velocity.y() + "|" + velocity.z()
                + "|" + owner.x() + "|" + owner.y() + "|" + owner.z()
                + "|" + ownerVelocity.x() + "|" + ownerVelocity.y() + "|" + ownerVelocity.z();
    }

    @Override public void onLaunch(TorpedoLaunchContext context) {
        target = new ContactTrack();
        terrain = context.terrain();
        planner = new PathPlanner(terrain, -50, 100, 80, 180, 2);
        route = List.of();
        leg = 0;
        planned = -100;
        launch = context.launchPosition();
        owner = launch;
        ownerVelocity = Vec3.ZERO;
        time = 0;
        hasMission = false;
        String data = context.missionData();
        if (data == null) return;
        String[] fields = data.split("\\|");
        if (fields.length != 13 || !fields[0].equals("S61")) return;
        try {
            double[] values = new double[12];
            for (int i = 0; i < values.length; i++) {
                values[i] = Double.parseDouble(fields[i + 1]);
                if (!Double.isFinite(values[i])) return;
            }
            target.seed(new Vec3(values[0], values[1], values[2]), new Vec3(values[3], values[4], values[5]));
            owner = new Vec3(values[6], values[7], values[8]);
            ownerVelocity = new Vec3(values[9], values[10], values[11]);
            hasMission = true;
        } catch (NumberFormatException ignored) {
            // Invalid mission data leaves the torpedo in a safe forward search.
        }
    }

    @Override public void onTick(TorpedoInput in, TorpedoOutput out) {
        time += in.deltaTimeSeconds();
        target.advance(in.deltaTimeSeconds());
        Vec3 p = in.self().position();
        if (in.activeSonarCooldownTicks() == 0) out.activeSonarPing();
        SonarContact c = select(in.activeSonarReturns(), p);
        if (c == null && target.activeAge > 4) c = select(in.sonarContacts(), p);
        if (c != null) target.observe(p, c, true);

        Vec3 aim;
        double range;
        if (target.valid) {
            Vec3 current = in.groundVelocity().subtract(in.velocity().linear());
            double lead = Geometry.interceptTime(target.position.subtract(p), target.velocity.subtract(current), 22.5);
            lead = Math.min(100, lead);
            aim = target.position.add(target.velocity.subtract(current).scale(lead));
            range = p.horizontalDistanceTo(target.position);
            // Unknown movement grows into a bounded search, never an orbit around the launcher.
            if (target.age > 18) {
                double sweep = Math.sin((target.age - 18) / 12) * Math.min(500, 6 * target.age);
                double h = Geometry.bearing(launch, target.position);
                aim = aim.add(new Vec3(Math.cos(h) * sweep, -Math.sin(h) * sweep, 0));
            }
        } else {
            range = 2000;
            aim = new Vec3(p.x() + Math.sin(in.self().heading()) * 2000,
                    p.y() + Math.cos(in.self().heading()) * 2000, launch.z());
        }
        if (range > 450 && (time - planned > 12 || aim.horizontalDistanceTo(routeGoal) > 400)) {
            route = planner.findCorridor(p.x(), p.y(), aim.x(), aim.y());
            leg = route.size() > 1 ? 1 : 0;
            routeGoal = aim;
            planned = time;
        }
        Vec3 steeringPoint = aim;
        if (range > 450) {
            while (leg < route.size()) {
                var point = route.get(leg);
                steeringPoint = new Vec3(point.x(), point.y(), aim.z());
                if (p.horizontalDistanceTo(steeringPoint) > 180) break;
                leg++;
            }
            if (leg >= route.size()) steeringPoint = aim;
        }
        double heading = Geometry.bearing(p, steeringPoint);
        double depth = aim.z();
        double horizon = Math.min(650, Math.max(100, range));
        for (double d = 0; d <= horizon; d += 40) {
            double h = in.self().heading() + Geometry.clamp(Geometry.angle(heading - in.self().heading()), -0.7, 0.7) * d / horizon;
            depth = Math.max(depth, terrain.elevationAt(p.x() + Math.sin(h) * d, p.y() + Math.cos(h) * d) + 28);
        }
        depth = Math.min(-8, depth);
        double desiredPitch = Geometry.clamp(Math.atan2(depth - p.z(), Math.max(70, Math.min(220, range))), -0.6, 0.6) - 0.005;
        double rudder = 2.6 * Geometry.angle(heading - in.self().heading()) - 9 * in.velocity().angular().z();
        out.setRudder(Geometry.clamp(rudder, -0.66, 0.66));
        out.setSternPlanes(Geometry.clamp(3 * (desiredPitch - in.self().pitch())
                - 7 * in.velocity().angular().y(), -0.66, 0.66));
        out.setThrottle(1);
        if (in.tick() % 10 == 0 && target.valid) {
            out.publishTarget(aim.x(), aim.y(), depth);
            out.publishDiagnostics(target.position.x(), target.position.y(), target.position.z(),
                    Math.atan2(target.velocity.x(), target.velocity.y()), target.velocity.length(),
                    aim.x(), aim.y(), depth, target.age < 3 ? "TERMINAL" : "SEARCH");
        }
    }

    private SonarContact select(List<SonarContact> contacts, Vec3 p) {
        SonarContact best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        Vec3 predictedOwner = owner.add(ownerVelocity.scale(Math.min(time, 25)));
        for (SonarContact c : contacts) {
            if (c.classification() == Classification.TORPEDO || c.estimatedSpeed() > 18 || !(c.range() > 0)) continue;
            double z = Double.isFinite(c.estimatedDepth()) ? c.estimatedDepth() : target.valid ? target.position.z() : p.z();
            double r = Math.sqrt(Math.max(1, c.range() * c.range() - (z - p.z()) * (z - p.z())));
            Vec3 measured = new Vec3(p.x() + Math.sin(c.bearing()) * r, p.y() + Math.cos(c.bearing()) * r, z);
            double error = target.valid ? measured.distanceTo(target.position) : c.range();
            if (hasMission) {
                double ownerDistance = measured.horizontalDistanceTo(predictedOwner);
                if (time < 35 && ownerDistance < 100 + time * 7 && ownerDistance < error) continue;
                // A source on the launch side of the engagement is unlikely to be the target.
                Vec3 axis = target.position.subtract(launch);
                if (time < 100 && measured.subtract(launch).dot(axis) < axis.dot(axis) * 0.12) continue;
                if (error > Math.max(900, 250 + target.age * 20)) continue;
            } else if (Math.abs(Geometry.angle(c.bearing() - Geometry.bearing(launch, p))) > 2) continue;
            double score = error - Math.min(100, c.signalExcess());
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best;
    }
}
