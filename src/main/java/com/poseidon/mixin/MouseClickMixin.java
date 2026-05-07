package com.poseidon.mixin;

import com.poseidon.core.FishingManager;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

@Mixin(Mouse.class)
public class MouseClickMixin {

    /**
     * Block right-click while Poseidon is waiting for a bite or reacting to one.
     * This prevents the player from accidentally cancelling the cast.
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void poseidon$blockRightClickWhenFishing(long window, MouseInput mouseInput, int action, CallbackInfo ci) {
        if (action != GLFW_PRESS) return;
        if (mouseInput.button() != GLFW_MOUSE_BUTTON_RIGHT) return;
        if (FishingManager.getInstance().shouldBlockRightClick()) {
            ci.cancel();
        }
    }
}
