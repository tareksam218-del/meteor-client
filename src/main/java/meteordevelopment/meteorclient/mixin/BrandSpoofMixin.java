package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AntiPacketKick;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientPacketListener.class)
public class BrandSpoofMixin {
    @ModifyArg(
        method = "handleLogin",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"),
        index = 0
    )
    private net.minecraft.network.protocol.Packet<?> spoofBrand(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket payload) {
            // Replace brand payload with vanilla
            return new net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket(
                net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket.BRAND,
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
                    .writeUtf("vanilla")
            );
        }
        return packet;
    }
}
