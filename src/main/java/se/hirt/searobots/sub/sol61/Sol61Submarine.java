package se.hirt.searobots.sub.sol61;

import java.util.List;
import se.hirt.searobots.api.*;
import se.hirt.searobots.api.SonarContact.Classification;

/** Independent Sol 6.1 controller: acquire, intercept, salvo, then open the range. */
public final class Sol61Submarine implements SubmarineController {
    private MatchConfig config;
    private Navigator navigator;
    private ContactTrack target;
    private double time, lastShot, salvoStart;
    private int shotsInSalvo, patrolIndex;
    private Vec3 patrol;
    private List<StrategicWaypoint> objectives = List.of();
    private int objectiveIndex;
    private double evadeUntil, evadeHeading;

    public Sol61Submarine() { }

    @Override public String name() { return "Sol 6.1"; }
    @Override public String shortName() { return "S6"; }
    @Override public TorpedoController createTorpedoController() { return new Sol61Torpedo(); }

    @Override public void onMatchStart(MatchContext context) {
        config = context.config();
        navigator = new Navigator(context);
        target = new ContactTrack();
        time = 0;
        lastShot = salvoStart = -1000;
        shotsInSalvo = patrolIndex = objectiveIndex = 0;
        evadeUntil = 0;
        patrol = null;
        objectives = List.of();
    }

    @Override public void setObjectives(List<StrategicWaypoint> waypoints) {
        objectives = List.copyOf(waypoints);
        objectiveIndex = 0;
    }

    @Override public void onTick(SubmarineInput in, SubmarineOutput out) {
        time += in.deltaTimeSeconds();
        target.advance(in.deltaTimeSeconds());
        Vec3 self = in.self().pose().position();
        SonarContact contact = chooseTarget(in.activeSonarReturns(), self);
        if (contact == null) contact = chooseTarget(in.sonarContacts(), self);
        if (contact != null) target.observe(self, contact, false);
        if (in.activeSonarCooldownTicks() == 0) out.activeSonarPing();

        if (target.valid && in.tick() % 10 == 0) {
            out.publishContactEstimate(new ContactEstimate(target.position.x(), target.position.y(),
                    Math.exp(-target.age / 30), Math.exp(-target.age / 90), target.uncertainty,
                    Math.atan2(target.velocity.x(), target.velocity.y()), target.velocity.length(), "tracked"));
        }

        double speed = 10;
        double depth = Math.max(config.ratedDepth() + 50, -200);
        Purpose purpose = Purpose.PATROL;
        Vec3 goal;
        if (objectiveIndex < objectives.size()) {
            var w = objectives.get(objectiveIndex);
            goal = new Vec3(w.x(), w.y(), w.preferredDepth());
            purpose = w.purpose();
            if (w.targetSpeed() >= 0) speed = w.targetSpeed();
            if (self.horizontalDistanceTo(goal) < w.arrivalRadius()) objectiveIndex++;
        } else if (target.valid && target.age < 120) {
            double distance = self.horizontalDistanceTo(target.position);
            double t = Geometry.interceptTime(target.position.subtract(self), target.velocity, 22);
            Vec3 intercept = target.position.add(target.velocity.scale(Math.min(t, 110)));
            depth = Geometry.clamp(target.position.z(), config.ratedDepth() + 60, -65);
            goal = new Vec3(intercept.x(), intercept.y(), depth);
            purpose = Purpose.INTERCEPT;
            speed = distance > 2600 ? 12 : 8;
            if (time - salvoStart > 150) shotsInSalvo = 0;
            boolean fresh = target.activeAge < 12 || (target.age < 2 && target.uncertainty < 350);
            boolean viable = fresh && distance > 300 && distance < 2900 && t < 185;
            double bearing = Geometry.bearing(self, intercept);
            if (viable) {
                out.publishFiringSolution(new FiringSolution(target.position.x(), target.position.y(),
                        Math.atan2(target.velocity.x(), target.velocity.y()), target.velocity.length(),
                        1 / (1 + target.uncertainty / 100)));
                double delay = shotsInSalvo < 4 ? 1.2 : 65;
                if (in.self().torpedoesRemaining() > 0 && time - lastShot > delay
                        && Math.abs(Geometry.angle(bearing - in.self().pose().heading())) < 0.65
                        && Math.abs(in.self().pose().pitch()) < 0.2) {
                    String mission = Sol61Torpedo.mission(target.position, target.velocity, self, in.groundVelocity());
                    out.launchTorpedo(new TorpedoLaunchCommand(bearing, 0,
                            Geometry.clamp(12, config.minFuseRadius(), config.maxFuseRadius()), mission));
                    lastShot = time;
                    if (shotsInSalvo == 0) salvoStart = time;
                    shotsInSalvo++;
                }
            }
            // After a salvo is on the way, open the range while the seekers work.
            if ((shotsInSalvo >= 4 && time - lastShot < 110) || in.self().torpedoesRemaining() == 0) {
                double away = Geometry.bearing(target.position, self);
                double h = away;
                goal = new Vec3(self.x() + Math.sin(h) * 1800, self.y() + Math.cos(h) * 1800, depth);
                speed = 15;
                purpose = Purpose.RALLY;
            }
        } else {
            if (patrol == null || self.horizontalDistanceTo(patrol) < 450 || in.tick() % 6000 == 0) {
                double h = patrolIndex++ * 2.399963229728653;
                double r = patrolIndex == 1 ? 0 : config.battleArea().extent() * 0.38;
                patrol = new Vec3(Math.sin(h) * r, Math.cos(h) * r, depth);
            }
            goal = patrol;
        }

        SonarContact threat = null;
        for (SonarContact c : in.activeSonarReturns()) {
            if (c.classification() != Classification.TORPEDO || !(c.range() > 0) || c.range() > 1000) continue;
            // The newly ejected weapon is expected in the bow arc for the first seconds.
            if (time - lastShot < 18 && Math.abs(Geometry.angle(c.bearing() - in.self().pose().heading())) < 0.9) continue;
            if (threat == null || c.range() < threat.range()) threat = c;
        }
        if (threat != null && time > evadeUntil) {
            double h1 = threat.bearing() + 2.5, h2 = threat.bearing() - 2.5;
            double turn1 = Math.abs(Geometry.angle(h1 - in.self().pose().heading()));
            double turn2 = Math.abs(Geometry.angle(h2 - in.self().pose().heading()));
            evadeHeading = turn1 < turn2 ? h1 : h2;
            evadeUntil = time + 105;
        }
        if (time < evadeUntil && objectiveIndex >= objectives.size()) {
            goal = new Vec3(self.x() + 1800 * Math.sin(evadeHeading),
                    self.y() + 1800 * Math.cos(evadeHeading), Math.max(config.ratedDepth() + 60, self.z() - 80));
            speed = 15;
            purpose = Purpose.EVADE;
        }
        navigator.steer(in, out, goal, speed, purpose);
        out.setStatus(purpose.name());
    }

    private SonarContact chooseTarget(List<SonarContact> contacts, Vec3 self) {
        SonarContact best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (SonarContact c : contacts) {
            if (c.classification() == Classification.TORPEDO || c.estimatedSpeed() > 18) continue;
            double score = c.classification() == Classification.UNKNOWN ? 500 : 0;
            if (target.valid && target.age < 60 && c.range() > 0) {
                Vec3 p = new Vec3(self.x() + Math.sin(c.bearing()) * c.range(),
                        self.y() + Math.cos(c.bearing()) * c.range(), target.position.z());
                score += p.horizontalDistanceTo(target.position);
            } else score -= c.signalExcess();
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best;
    }
}
