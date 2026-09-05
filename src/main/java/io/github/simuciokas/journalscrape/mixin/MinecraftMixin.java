package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.JournalScrape;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The clock the scrape runs on. Every page turn is a server round trip, and a mod cannot sleep
 * without freezing the client the reply arrives on - so the walk advances one step per tick.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void journalscrape$tick(CallbackInfo ci) {
        JournalScrape.tick((Minecraft) (Object) this);
    }
}
