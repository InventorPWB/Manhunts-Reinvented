package world.inventorpwb.manhunt.mixins;

import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import world.inventorpwb.manhunt.Manhunt;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    public void broadcast(Text message, boolean overlay, CallbackInfo ci) {
        if (!(message.getString().contains("joined") || message.getString().contains("left"))) return;
        if (!Manhunt.INSTANCE.isActive()) return;

        String[] parts = message.getString().split(" ");
        if (parts.length == 0) return;

        String playerName = parts[0];
        if (Manhunt.INSTANCE.isDead(Text.of(playerName))) {
            LOGGER.info("Player dead, cancelling");
            ci.cancel();
        }
    }
}
