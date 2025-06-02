package world.inventorpwb.manhunt.mixins;

import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.inventorpwb.manhunt.Config;
import world.inventorpwb.manhunt.Manhunt;


@Mixin(ServerPlayNetworkHandler.class)
public abstract class RemoveChatMixin {

    @Shadow public abstract ServerPlayerEntity getPlayer();

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void init(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (Manhunt.INSTANCE.isActive() && Manhunt.INSTANCE.isModeImpostor() && Config.disableImpostorGameChat) {
            this.getPlayer().sendMessageToClient(Text.of("Chat is disabled."),false);
            ci.cancel();
        }
    }
}


