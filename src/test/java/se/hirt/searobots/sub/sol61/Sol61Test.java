package se.hirt.searobots.sub.sol61;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import se.hirt.searobots.api.*;
import se.hirt.searobots.engine.*;

class Sol61Test {
    @Test void compilesAgainstApiAlone() throws Exception {
        Path api = Path.of(SubmarineController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output = Path.of("target", "standalone-check");
        Files.createDirectories(output);
        var args = new ArrayList<>(List.of("--release", "25", "-proc:none", "-cp", api.toString(), "-d", output.toString()));
        try (var files = Files.walk(Path.of("src", "main", "java"))) {
            files.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
        }
        var errors = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, args.toArray(String[]::new)),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test void computesInterceptionInWorldCoordinates() {
        assertEquals(100, Geometry.interceptTime(new Vec3(0, 2000, 0), Vec3.ZERO, 20), 1e-8);
        assertEquals(200, Geometry.interceptTime(new Vec3(0, 2000, 0), new Vec3(0, 10, 0), 20), 1e-8);
        Vec3 r = new Vec3(400, 1600, -60), v = new Vec3(8, -3, 0);
        double t = Geometry.interceptTime(r, v, 23);
        assertEquals(t * 23, r.add(v.scale(t)).length(), 1e-7);
        assertTrue(Double.isFinite(Geometry.interceptTime(r, new Vec3(80, 0, 0), 23)));
    }

    @Test void passiveRangeCannotOverwriteRecentActiveFix() {
        ContactTrack track = new ContactTrack();
        track.observe(new Vec3(0, 0, -100), contact(1000, true), false);
        Vec3 fix = track.position;
        for (int i = 0; i < 250; i++) {
            track.advance(0.02);
            track.observe(new Vec3(0, 0, -100), contact(8000, false), false);
        }
        assertEquals(fix, track.position);
        assertEquals(5, track.activeAge, 1e-8);
    }

    @Test void survivesTerrainPatrolUnderLeagueLimits() {
        for (long seed : new long[] {0x2c, 0x13, 0x61, 0x20261126}) {
            var config = MatchConfig.withDefaults(seed).withMatchDurationTicks(20 * 60 * 50);
            var world = new WorldGenerator().generate(config);
            var sim = new SimulationLoop();
            sim.setSpeedMultiplier(1e9);
            sim.setControllerLimits(SimulationLoop.ControllerLimits.leagueDefaults());
            final int[] hp = {1000};
            sim.run(world, List.of(new Sol61Submarine()), List.of(VehicleConfig.submarine()), new SimulationListener() {
                @Override public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
                    hp[0] = subs.getFirst().hp();
                    assertTrue(config.battleArea().contains(subs.getFirst().pose().position().x(),
                            subs.getFirst().pose().position().y()), "Left battle area on seed " + seed);
                }
                @Override public void onMatchEnd() { }
            });
            assertEquals("", sim.forfeitReason(0), "Seed " + seed);
            assertEquals(1000, hp[0], "Patrol terrain damage on seed " + seed);
        }
    }

    private static SonarContact contact(double range, boolean active) {
        return new SonarContact(0, 30, range, active, 0, 0.01, 20,
                Double.NaN, 0.8, Double.NaN, -100, SonarContact.Classification.SUBMARINE);
    }
}
