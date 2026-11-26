package se.hirt.searobots.sub.sol61;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import se.hirt.searobots.api.*;
import se.hirt.searobots.engine.*;

/** Read-only simulator telemetry. Enemy truth never enters a controller. */
public final class TraceMatch {
    private TraceMatch() { }

    public static void main(String[] args) throws Exception {
        String opponent = args.length > 0 ? args[0] : "default";
        long seed = Long.parseUnsignedLong(args.length > 1 ? args[1] : "2c", 16);
        boolean swap = args.length > 2 && Boolean.parseBoolean(args[2]);
        int minutes = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        var resolved = ControllerLoader.resolve(opponent);
        SubmarineController own = new Sol61Submarine(), other = resolved.factory().get();
        var config = MatchConfig.withDefaults(seed).withMatchDurationTicks(minutes * 60 * 50);
        var world = new WorldGenerator().generate(config);
        var sim = new SimulationLoop();
        sim.setSpeedMultiplier(1e9);
        sim.setControllerLimits(SimulationLoop.ControllerLimits.leagueDefaults());
        int slot = swap ? 1 : 0;
        String prefix = opponent + "-" + Long.toUnsignedString(seed, 16) + "-" + swap;
        Files.createDirectories(Path.of("target", "traces"));
        try (var subs = new PrintWriter(Files.newBufferedWriter(Path.of("target", "traces", prefix + "-subs.csv")));
             var torps = new PrintWriter(Files.newBufferedWriter(Path.of("target", "traces", prefix + "-torps.csv")))) {
            subs.println("seconds,slot,x,y,z,heading,speed,hp,torpedoes,status,targetError,floorGap");
            torps.println("seconds,id,owner,x,y,z,speed,range,estimateError,depthError,headingError,phase,fuel");
            sim.run(world, swap ? List.of(other, own) : List.of(own, other),
                    List.of(VehicleConfig.submarine(), VehicleConfig.submarine()), new SimulationListener() {
                @Override public void onTick(long tick, List<SubmarineSnapshot> s, List<TorpedoSnapshot> t) {
                    if (tick % 250 != 0) return;
                    for (var sub : s) {
                        var p = sub.pose().position();
                        var enemy = s.get(1 - sub.id()).pose().position();
                        double error = sub.contactEstimates().isEmpty() ? -1
                                : Math.hypot(sub.contactEstimates().getFirst().x() - enemy.x(),
                                        sub.contactEstimates().getFirst().y() - enemy.y());
                        subs.printf(Locale.ROOT, "%.1f,%d,%.1f,%.1f,%.1f,%.3f,%.2f,%d,%d,%s,%.1f,%.1f%n",
                                tick / 50., sub.id(), p.x(), p.y(), p.z(), sub.pose().heading(), sub.speed(),
                                sub.hp(), sub.torpedoesRemaining(), sub.status(), error,
                                p.z() - world.terrain().elevationAt(p.x(), p.y()));
                    }
                    for (var torp : t) {
                        var p = torp.pose().position();
                        var enemy = s.get(1 - torp.ownerId()).pose().position();
                        double range = p.distanceTo(enemy);
                        torps.printf(Locale.ROOT, "%.1f,%d,%d,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f,%.1f,%.3f,%s,%.1f%n",
                                tick / 50., torp.id(), torp.ownerId(), p.x(), p.y(), p.z(), torp.speed(), range,
                                Math.hypot(torp.diagEstX() - enemy.x(), torp.diagEstY() - enemy.y()),
                                torp.diagEstZ() - enemy.z(),
                                Geometry.angle(Geometry.bearing(p, enemy) - torp.pose().heading()),
                                torp.diagPhase(), torp.fuelRemaining());
                    }
                    subs.flush();
                    torps.flush();
                }
                @Override public void onMatchEnd() { }
            });
        }
        var result = sim.matchOutcome();
        System.out.println("TRACE " + prefix + " slot=" + slot + " " + result);
    }
}
