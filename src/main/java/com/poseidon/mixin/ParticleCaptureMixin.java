package com.poseidon.mixin;

import com.poseidon.core.ParticleWatch;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every particle the client spawns (both local effects and server-sent
 * {@code ClientboundLevelParticlesPacket}s funnel through {@link ParticleEngine#createParticle}) into
 * {@link ParticleWatch}, so Poseidon can spot the blue "bobber not in water" particle burst. Read-only —
 * it never alters the particle. Gated inside {@link ParticleWatch#record} so it costs nothing when the
 * feature/debug are off.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleCaptureMixin {

    // require = 0: if this version maps createParticle differently the feature just goes inert
    // (no particle capture) instead of crashing Poseidon on load. If "Log Bobber Particles" shows
    // nothing, this target needs adjusting.
    @Inject(method = "createParticle", at = @At("HEAD"), require = 0)
    private void poseidon$capture(ParticleOptions particleData, double x, double y, double z,
                                  double xSpeed, double ySpeed, double zSpeed,
                                  CallbackInfoReturnable<Particle> cir) {
        ParticleWatch.getInstance().record(particleData, x, y, z);
    }
}
