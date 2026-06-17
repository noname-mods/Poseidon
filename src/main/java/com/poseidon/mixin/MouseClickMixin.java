package com.poseidon.mixin;

import com.poseidon.core.FishingManager;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

@Mixin(MouseHandler.class)
public class MouseClickMixin {

    /**
     * Block right-click while Poseidon is waiting for a bite or reacting to one.
     * This prevents the player from accidentally cancelling the cast.
     *
     * In MC 26.1.2 onPress(long, int, int, int) was replaced by
     * onButton(long, MouseButtonInfo, int). The button integer is now accessed
     * via MouseButtonInfo.button().
     */
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void poseidon$blockRightClickWhenFishing(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (action != GLFW_PRESS) return;
        if (buttonInfo.button() != GLFW_MOUSE_BUTTON_RIGHT) return;
        if (FishingManager.getInstance().shouldBlockRightClick()) {
            ci.cancel();
        }
    }
}
