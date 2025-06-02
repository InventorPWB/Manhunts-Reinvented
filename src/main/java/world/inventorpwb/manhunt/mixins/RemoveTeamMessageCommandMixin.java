package world.inventorpwb.manhunt.mixins;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.TeamMsgCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.inventorpwb.manhunt.Config;

@Mixin(TeamMsgCommand.class)
public abstract class RemoveTeamMessageCommandMixin {
    @Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private static void init(CommandDispatcher<ServerCommandSource> dispatcher, CallbackInfo ci) {
        if (Config.disableMessaging) ci.cancel();
    }
}