package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ClientPacketListener.class)
public class ChannelSpoofMixin {
    // Channels that reveal mod presence — block outgoing registration of these
    private static final Set<String> BLOCKED_NAMESPACES = Set.of(
        "fabric", "forge", "fml", "meteor-client", "baritone",
        "wurst", "liquidbounce", "future", "rise", "sigma"
    );

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void blockModChannels(net.minecraft.network.protocol.Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            ResourceLocation id = custom.identifier();
            if (id != null && BLOCKED_NAMESPACES.contains(id.getNamespace())) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void blockModQueries(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        ResourceLocation id = packet.identifier();
        if (id != null && BLOCKED_NAMESPACES.contains(id.getNamespace())) {
            ci.cancel();
        }
    }
}
