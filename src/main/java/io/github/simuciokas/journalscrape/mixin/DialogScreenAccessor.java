package io.github.simuciokas.journalscrape.mixin;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.Dialog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code DialogScreen}'s dialog is a private final field with no getter, and it is the whole point
 * of this mod - every page of journal content is in there as structured data.
 *
 * <p>The field is declared {@code private final T dialog} where {@code T extends Dialog}, so its
 * ERASED descriptor is {@code Lnet/minecraft/server/dialog/Dialog;}. An accessor returning Object
 * matches nothing and, because the mixin config is {@code required}, takes the game down at
 * startup rather than merely disabling the mod.
 */
@Mixin(DialogScreen.class)
public interface DialogScreenAccessor {

    @Accessor("dialog")
    Dialog journalscrape$getDialog();
}
