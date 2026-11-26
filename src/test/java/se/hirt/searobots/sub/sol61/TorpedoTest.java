package se.hirt.searobots.sub.sol61;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.hirt.searobots.api.*;

class TorpedoTest {
    @Test void ignoresLoudLauncherBehindWeapon() {
        var controller = new Sol61Torpedo();
        controller.onLaunch(context(Sol61Torpedo.mission(new Vec3(0, 1500, -100), Vec3.ZERO,
                new Vec3(0, 0, -100), Vec3.ZERO)));
        var output = new Capture();
        controller.onTick(input(List.of(contact(Math.PI, 100, 80), contact(0, 1400, 15))), output);
        assertTrue(output.targetY > 1000, "Seeker must retain the hostile contact ahead of the launch position");
        assertEquals(0, output.rudder, 0.01);
        assertFalse(output.detonated);
    }

    @Test void missingAndMalformedMissionRemainControllable() {
        for (String data : Arrays.asList(null, "", "S61|NaN", "invalid|1|2", "S61|NaN|0|0|0|0|0|0|0|0|0|0|0")) {
            var controller = new Sol61Torpedo();
            controller.onLaunch(context(data));
            var output = new Capture();
            controller.onTick(input(List.of()), output);
            assertTrue(Double.isFinite(output.rudder));
            assertTrue(Double.isFinite(output.planes));
            assertEquals(1, output.throttle);
            assertFalse(output.detonated);
        }
    }

    private static TorpedoLaunchContext context(String data) {
        double[] floor = new double[81 * 81];
        Arrays.fill(floor, -400);
        return new TorpedoLaunchContext(MatchConfig.withDefaults(61),
                new TerrainMap(floor, 81, 81, -4000, -4000, 100), new Vec3(0, 40, -100), 0, 0, data);
    }

    private static SonarContact contact(double bearing, double range, double signal) {
        return new SonarContact(bearing, signal, range, true, 0, 0.01, range * 0.02,
                Double.NaN, 0.8, Double.NaN, -100, SonarContact.Classification.SUBMARINE);
    }

    private static TorpedoInput input(List<SonarContact> contacts) {
        return new TorpedoInput() {
            @Override public long tick() { return 10; }
            @Override public double deltaTimeSeconds() { return 0.02; }
            @Override public Pose self() { return Pose.at(0, 100, -100, 0); }
            @Override public Velocity velocity() { return new Velocity(new Vec3(0, 20, 0), Vec3.ZERO); }
            @Override public double speed() { return 20; }
            @Override public double fuelRemaining() { return 200; }
            @Override public List<SonarContact> activeSonarReturns() { return contacts; }
        };
    }

    private static final class Capture implements TorpedoOutput {
        double rudder, planes, throttle, targetY;
        boolean detonated;
        @Override public void setRudder(double v) { rudder = v; }
        @Override public void setSternPlanes(double v) { planes = v; }
        @Override public void setThrottle(double v) { throttle = v; }
        @Override public void detonate() { detonated = true; }
        @Override public void publishTarget(double x, double y, double z) { targetY = y; }
    }
}
