package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundResourcePackPushPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;

@Mixin(ClientPacketListener.class)
public class BlockLocalUrlMixin {
    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void blockLocalUrls(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        try {
            URI uri = URI.create(packet.url());
            String host = uri.getHost();
            if (host == null) { ci.cancel(); return; }
            // Block local/private addresses used for fingerprinting
            if (host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.startsWith("172.16.")
                || host.endsWith(".local")) {
                ci.cancel();
            }
        } catch (Exception ignored) {
            ci.cancel(); // malformed URL — block it
        }
    }
}
