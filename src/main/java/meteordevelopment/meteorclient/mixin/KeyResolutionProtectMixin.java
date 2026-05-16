package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class KeyResolutionProtectMixin {
    private static final ResourceLocation KEY_RESOLUTION_CHANNEL =
        ResourceLocation.withDefaultNamespace("registered");

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void blockKeyResolution(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        // Block packets that probe which mod channels are registered
        // (used to detect mods by their registered plugin channels)
        ResourceLocation id = packet.identifier();
        if (id != null && (id.getPath().equals("register")
                        || id.getPath().equals("unregister")
                        || id.equals(KEY_RESOLUTION_CHANNEL))) {
            ci.cancel();
        }
    }
}
