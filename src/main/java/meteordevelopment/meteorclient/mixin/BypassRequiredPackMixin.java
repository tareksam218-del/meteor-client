package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class BypassRequiredPackMixin {
    @Shadow public abstract void send(net.minecraft.network.protocol.Packet<?> packet);

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void bypassRequired(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (packet.required()) {
            // Accept the pack server-side without actually applying it client-side
            send(new ServerboundResourcePackPacket(
                packet.id(),
                ServerboundResourcePackPacket.Action.ACCEPTED
            ));
            send(new ServerboundResourcePackPacket(
                packet.id(),
                ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
            ));
            ci.cancel();
        }
    }
}
