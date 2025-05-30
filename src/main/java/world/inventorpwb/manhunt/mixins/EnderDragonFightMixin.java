package world.inventorpwb.manhunt.mixins;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import world.inventorpwb.manhunt.Manhunt;

@Mixin(EnderDragonFight.class)
public class EnderDragonFightMixin {
    /**
     * EnderDragonFight has a private field "world" of type ServerWorld.
     * We shadow it so we can grab the server instance.
     */
    @Shadow @Final private ServerWorld world;

    /**
     * Inject at the very start of dragonKilled(EnderDragonEntity).
     * This is called once the Ender Dragon is marked killed.
     */
    @Inject(
            method = "dragonKilled",        // method signature: void dragonKilled(EnderDragonEntity)
            at = @At("HEAD")
    )
    private void onDragonKilled(EnderDragonEntity dragon, CallbackInfo ci) {
        MinecraftServer server = world.getServer();
        Manhunt.onEnderDragonDeath(server);
    }
}
