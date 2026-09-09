package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.ScrapeOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the scrape cover over whatever screen is open - the journal grid, the entry dialog, or
 * anything else that appears mid-run.
 *
 * <p>The hook is the WITH-TOOLTIP variant because it is the outermost of the two, so the cover is
 * added after the screen's own contents and its tooltips and lands on top of them. Injecting into
 * the inner extractRenderState would leave tooltips drawing over the cover.
 */
@Mixin(Screen.class)
public class ScreenOverlayMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void journalscrape$cover(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                     float partialTick, CallbackInfo ci) {
        ScrapeOverlay.draw(extractor);
    }
}
