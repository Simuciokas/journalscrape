package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.JournalScrape;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Escape ends a running scrape.
 *
 * <p>Closing the window is what a player reaches for when they want out, and without this the
 * instinct FIGHTS the mod: the walk sees the grid gone, re-issues the command and the journal opens
 * again, which is indistinguishable from being trapped.
 *
 * <p>The key event is hooked rather than {@code onClose}, because the mod closes and reopens these
 * screens constantly by itself and the server closes them too - a close is ordinary, whereas an
 * Escape keystroke while a scrape is running can only have come from the player. The event is not
 * cancelled: the screen should still close, since the run is ending anyway.
 */
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"))
    private void journalscrape$stopOnEscape(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        JournalScrape.onKeyPressed(event.key());
    }
}
