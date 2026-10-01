package fr.lkdm.homelink.tasks.compat.rei;

import fr.lkdm.homelink.tasks.compat.ViewerInfo;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minecraft.world.item.ItemStack;

/** Optional REI integration, loaded by REI only: information pages for every HomeLink Tasks item. */
@REIPluginClient
public final class TasksReiPlugin implements REIClientPlugin {
    @Override public void registerDisplays(DisplayRegistry registry) {
        ViewerInfo.pages().forEach((item, lines) -> registry.add(DefaultInformationDisplay
                .createFromEntry(EntryStacks.of(item), new ItemStack(item).getHoverName()).lines(lines)));
    }
}
