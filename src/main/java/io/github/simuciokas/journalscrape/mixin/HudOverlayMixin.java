package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.ScrapeOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the scrape cover on the HUD pass, which is the only pass that happens when NO screen is
 * open - the frames between closing the dialog and the journal reopening, where the world shows
 * through. The screen pass handles the rest.
 */
@Mixin(Hud.class)
public class HudOverlayMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void journalscrape$cover(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        ScrapeOverlay.draw(extractor);
    }
}
