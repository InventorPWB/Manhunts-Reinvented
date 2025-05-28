package world.inventorpwb.manhunt.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.server.world.ServerWorld;

@Mixin(LodestoneTrackerComponent.class)
public class Tracker {
    @Inject(method = "forWorld", at = @At("HEAD"), cancellable = true)
    public void forWorld(ServerWorld world, CallbackInfoReturnable<LodestoneTrackerComponent> cir) {
        cir.setReturnValue((LodestoneTrackerComponent) (Object) this);
    }
}
