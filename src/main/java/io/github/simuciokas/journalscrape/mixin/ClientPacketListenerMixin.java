package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.JournalScrape;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices when YOU run the journal command and arms the scrape.
 *
 * <p>Deliberately does NOT cancel: the command still has to reach the server, because the scrape
 * works by driving the very GUI that command opens.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /**
     * A chat button's {@code run_command} does NOT come through {@code sendCommand} - clicking a
     * component calls {@code sendUnattendedCommand}. Hooking only the typed path meant the
     * [upload] button's command sailed past this mod and reached the server, which answered with
     * an unknown command and nothing was ever uploaded.
     */
    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void journalscrape$clickedCommand(String command, Screen screen, CallbackInfo ci) {
        if (JournalScrape.onUploadCommand(command)) {
            ci.cancel();
        }
    }

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void journalscrape$armOnJournal(String command, CallbackInfo ci) {
        // The upload button's command is ours alone - swallow it rather than letting the server
        // answer "unknown command". The journal command is deliberately NOT cancelled: the scrape
        // works by driving the GUI that command opens.
        if (JournalScrape.onUploadCommand(command)) {
            ci.cancel();
            return;
        }
        JournalScrape.onCommandSent(command);
    }
}
