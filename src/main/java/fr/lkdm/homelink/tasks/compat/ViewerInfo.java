package fr.lkdm.homelink.tasks.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Information pages shown by JEI and REI, shared by both plugins so neither viewer is referenced
 * here. A page comes from the translation {@code info.homelink_tasks.<item>}; variants of one family share
 * a page through a family key (tier or size suffix, then oxidation or wax prefix removed).
 */
public final class ViewerInfo {
    private static final String MOD_ID = "homelink_tasks";

    private ViewerInfo() { }

    /** @return each item of this mod that has a page, with its paragraphs in the current language */
    public static Map<Item, List<Component>> pages() {
        Map<Item, List<Component>> pages = new LinkedHashMap<>();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            var id = entry.getKey().location();
            if (!id.getNamespace().equals(MOD_ID)) continue;
            for (String key : keys(id.getPath())) {
                if (!I18n.exists(key)) continue;
                List<Component> paragraphs = new ArrayList<>();
                for (String paragraph : I18n.get(key).split("\n")) if (!paragraph.isBlank()) paragraphs.add(Component.literal(paragraph));
                pages.put(entry.getValue(), paragraphs);
                break;
            }
        }
        return pages;
    }

    /** @return translation keys tried for an item, most specific first */
    static List<String> keys(String path) {
        List<String> names = new ArrayList<>();
        names.add(path);
        String family = path.replaceFirst("_([0-9]+|i{1,3}|small|medium|large)$", "");
        if (!family.equals(path)) names.add(family);
        for (String name : List.copyOf(names)) {
            String stripped = name;
            while (stripped.matches("(waxed|exposed|weathered|oxidized)_.+")) {
                stripped = stripped.substring(stripped.indexOf('_') + 1);
                names.add(stripped);
            }
        }
        return names.stream().distinct().map(name -> "info." + MOD_ID + "." + name).toList();
    }
}
