package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.TelemetryProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientTelemetryManager.class)
public class TelemetryBlockMixin {
    @Inject(method = "createEventLogger", at = @At("HEAD"), cancellable = true)
    private void blockEventLogger(CallbackInfoReturnable<TelemetryEventSender> cir) {
        // Return a no-op sender — no data goes to Mojang
        cir.setReturnValue(event -> {});
    }
}
