package world.inventorpwb.manhunt.mixins;

import net.minecraft.advancement.AdvancementEntry;       // <— note: AdvancementEntry, not Advancement
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.inventorpwb.manhunt.Manhunt;

@Mixin(PlayerAdvancementTracker.class)
public class PlayerAdvancementTrackerMixin {

    /**
     * Inject at the very start of grantCriterion(AdvancementEntry, String).
     * If shouldBlockAdvancements(...) returns true, we cancel and force return false.
     */
    @Inject(
            method = "grantCriterion(Lnet/minecraft/advancement/AdvancementEntry;Ljava/lang/String;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onGrantCriterionEarly(AdvancementEntry advancementEntry, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        // Retrieve the ServerPlayerEntity (owner) via our accessor:
        ServerPlayerEntity player = ((PlayerAdvancementTrackerAccessor) this).getOwner();

        if (Manhunt.INSTANCE.isDead(player.getName())) {
            // Immediately bail out. Return false → “criterion not granted,”
            // so no advancement progress, no packet, no client‐side toast.
            cir.setReturnValue(false);
            cir.cancel();
        }
        // If shouldBlockAdvancements(...) is false, do nothing here,
        // and let vanilla process the advancement normally.
    }
}
