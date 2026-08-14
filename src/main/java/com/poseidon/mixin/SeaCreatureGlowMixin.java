package com.poseidon.mixin;

import com.poseidon.core.FishingManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the sea creatures you caught appear glowing by overriding
 * {@link Entity#isCurrentlyGlowing()} on the client side.
 *
 * <p>Render-only — no packets are sent and the entity's data is untouched. The glow set is
 * published by {@link FishingManager} (mob IDs resolved from the tracked name plates).</p>
 */
@Mixin(Entity.class)
public abstract class SeaCreatureGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void poseidon$forceGlow(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return; // already glowing — nothing to override
        Entity self = (Entity) (Object) this;
        if (FishingManager.getInstance().isSeaCreatureGlowing(self.getId())) {
            cir.setReturnValue(true);
        }
    }
}
