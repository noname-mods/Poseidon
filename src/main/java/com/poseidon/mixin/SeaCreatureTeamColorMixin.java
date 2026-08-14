package com.poseidon.mixin;

import com.poseidon.core.FishingManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints the sea-creature glow by returning the configured colour from
 * {@link Entity#getTeamColor()}, which the vanilla outline renderer uses.
 *
 * <p>Only overrides entities in the glow set; everything else keeps its normal team colour.
 * Note the explicit early-return rather than a ternary — mixing {@code int} and a boxed value in
 * a conditional unboxes the whole expression and NPEs on the non-highlighted path (a real bug
 * that bit ESP).</p>
 */
@Mixin(Entity.class)
public abstract class SeaCreatureTeamColorMixin {

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void poseidon$glowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        FishingManager mgr = FishingManager.getInstance();
        if (!mgr.isSeaCreatureGlowing(self.getId())) return; // leave the vanilla team colour
        cir.setReturnValue(mgr.getSeaCreatureGlowColor(self.getId()));
    }
}
