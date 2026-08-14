package com.poseidon.core;

import com.playerapi.Scheduler;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Small ring buffer of recently-spawned particles (type id + position + tick), fed by
 * {@code ParticleCaptureMixin}. Poseidon uses it to detect the blue "bobber not in water" particle
 * burst Hypixel spawns when its in-water/lava detection fails on a cast, so the bot can force a recast
 * instead of waiting on a bobber that will never bite.
 *
 * <p>Recording is gated by {@link #setActive(boolean)} — when the feature (and its debug) are both off
 * the mixin does no work. Access is synchronized because particle creation and the fishing tick both
 * touch this on the client thread and we want to stay safe regardless of call site.</p>
 */
public final class ParticleWatch {

    private static final ParticleWatch INSTANCE = new ParticleWatch();
    public static ParticleWatch getInstance() { return INSTANCE; }
    private ParticleWatch() {}

    /** Drop particle records older than this many ticks (~2s). */
    private static final int TTL_TICKS = 40;
    /** Cap the buffer so a particle storm can't grow it without bound. */
    private static final int MAX_HITS = 512;

    private record Hit(String type, double x, double y, double z, long tick) {}

    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private volatile boolean active = false;

    /** Enable/disable recording — set from the fishing tick based on config (feature or debug on). */
    public void setActive(boolean a) { active = a; }
    public boolean isActive() { return active; }

    /** Record a spawned particle. No-op when inactive. Called from the capture mixin. */
    public void record(ParticleOptions opts, double x, double y, double z) {
        if (!active || opts == null) return;
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(opts.getType());
        if (key == null) return;
        long now = Scheduler.getCurrentTick();
        synchronized (hits) {
            hits.addLast(new Hit(key.toString(), x, y, z, now));
            prune(now);
            while (hits.size() > MAX_HITS) hits.pollFirst();
        }
    }

    private void prune(long now) {
        while (!hits.isEmpty() && now - hits.peekFirst().tick() > TTL_TICKS) hits.pollFirst();
    }

    /** Count recorded particles of the given types within {@code radius} of a point, since {@code sinceTick}. */
    public int countNear(double x, double y, double z, double radius, long sinceTick, Set<String> types) {
        double r2 = radius * radius;
        int c = 0;
        synchronized (hits) {
            for (Hit h : hits) {
                if (h.tick() < sinceTick) continue;
                if (types != null && !types.contains(h.type())) continue;
                double dx = h.x() - x, dy = h.y() - y, dz = h.z() - z;
                if (dx * dx + dy * dy + dz * dz <= r2) c++;
            }
        }
        return c;
    }

    /** Distinct particle type ids seen within {@code radius} of a point since {@code sinceTick} (for debug). */
    public Set<String> typesNear(double x, double y, double z, double radius, long sinceTick) {
        double r2 = radius * radius;
        Set<String> out = new HashSet<>();
        synchronized (hits) {
            for (Hit h : hits) {
                if (h.tick() < sinceTick) continue;
                double dx = h.x() - x, dy = h.y() - y, dz = h.z() - z;
                if (dx * dx + dy * dy + dz * dz <= r2) out.add(h.type());
            }
        }
        return out;
    }
}
