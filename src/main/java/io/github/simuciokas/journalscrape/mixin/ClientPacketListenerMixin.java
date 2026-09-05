package io.github.simuciokas.journalscrape.mixin;

import io.github.simuciokas.journalscrape.JournalScrape;
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

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void journalscrape$armOnJournal(String command, CallbackInfo ci) {
        JournalScrape.onCommandSent(command);
    }
}
