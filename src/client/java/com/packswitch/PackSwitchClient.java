package com.packswitch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class PackSwitchClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("PackSwitch");
    public static final List<String> SELECTED_PACKS = new ArrayList<>();
    public static boolean packsEnabled = false;
    private static Path configFile;
    private static KeyBinding toggleKey;
    private static KeyBinding guiKey;

    @Override
    public void onInitializeClient() {
        configFile = MinecraftClient.getInstance().runDirectory.toPath().resolve("packswitch.txt");
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.packswitch.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.packswitch"
        ));
        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.packswitch.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.packswitch"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (guiKey.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new PackSelectorScreen());
                }
            }
            while (toggleKey.wasPressed()) {
                handleToggle(client);
            }
        });

        LOGGER.info("PackSwitch loaded! Press O to select packs, P to toggle.");
    }

    public static void loadConfig() {
        SELECTED_PACKS.clear();
        try {
            if (Files.exists(configFile)) {
                for (String line : Files.readAllLines(configFile)) {
                    String t = line.trim();
                    if (!t.isEmpty()) SELECTED_PACKS.add(t);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load config", e);
        }
    }

    public static void saveConfig() {
        try {
            Files.write(configFile, SELECTED_PACKS, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    private void handleToggle(MinecraftClient client) {
        if (client.player == null) return;
        if (SELECTED_PACKS.isEmpty()) {
            client.player.sendMessage(Text.literal("§e[PackSwitch] No packs selected! Press O to choose packs."), true);
            return;
        }
        ResourcePackManager pm = client.getResourcePackManager();
        Collection<String> current = pm.getEnabledProfiles().stream()
                .map(ResourcePackProfile::getId)
                .collect(Collectors.toCollection(ArrayList::new));

        if (!packsEnabled) {
            for (String name : SELECTED_PACKS) {
                String id = "file/" + name;
                if (pm.getProfile(id) != null && !current.contains(id)) current.add(id);
            }
            applyPacks(client, pm, current);
            packsEnabled = true;
            client.player.sendMessage(Text.literal("§a[PackSwitch] ✔ Packs ON"), true);
        } else {
            for (String name : SELECTED_PACKS) current.remove("file/" + name);
            applyPacks(client, pm, current);
            packsEnabled = false;
            client.player.sendMessage(Text.literal("§7[PackSwitch] Packs OFF"), true);
        }
    }

    public static void applyPacks(MinecraftClient client, ResourcePackManager pm, Collection<String> packs) {
        client.options.resourcePacks.clear();
        client.options.resourcePacks.addAll(
                packs.stream().filter(id -> id.startsWith("file/")).map(id -> id.substring(5)).toList()
        );
        client.options.write();
        pm.setEnabledProfiles(packs);
        client.reloadResourcesConcurrently();
    }

    // ── In-game GUI Screen ────────────────────────────────────────────────────

    public static class PackSelectorScreen extends Screen {
        private final List<String> allPacks = new ArrayList<>();
        private final List<Boolean> checked = new ArrayList<>();
        private int scroll = 0;
        private static final int ROW = 22;
        private static final int TOP = 55;

        public PackSelectorScreen() {
            super(Text.literal("§lPackSwitch §r§7— Select Packs to Toggle"));
        }

        @Override
        protected void init() {
            allPacks.clear();
            checked.clear();
            ResourcePackManager pm = client.getResourcePackManager();
            for (ResourcePackProfile p : pm.getProfiles()) {
                if (p.getId().startsWith("file/")) {
                    String name = p.getId().substring(5);
                    allPacks.add(name);
                    checked.add(SELECTED_PACKS.contains(name));
                }
            }

            // Done button
            addDrawableChild(ButtonWidget.builder(Text.literal("✔ Done"), btn -> {
                SELECTED_PACKS.clear();
                for (int i = 0; i < allPacks.size(); i++) {
                    if (checked.get(i)) SELECTED_PACKS.add(allPacks.get(i));
                }
                saveConfig();
                packsEnabled = false;
                close();
            }).dimensions(width / 2 - 55, height - 28, 110, 20).build());

            // Select All button
            addDrawableChild(ButtonWidget.builder(Text.literal("Select All"), btn -> {
                for (int i = 0; i < checked.size(); i++) checked.set(i, true);
            }).dimensions(width / 2 - 170, height - 28, 90, 20).build());

            // Clear All button
            addDrawableChild(ButtonWidget.builder(Text.literal("Clear All"), btn -> {
                for (int i = 0; i < checked.size(); i++) checked.set(i, false);
            }).dimensions(width / 2 + 80, height - 28, 90, 20).build());
        }

        private int visibleRows() {
            return (height - TOP - 35) / ROW;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0 && mx >= 15 && mx <= width - 15 && my >= TOP) {
                int idx = (int)((my - TOP) / ROW) + scroll;
                if (idx >= 0 && idx < allPacks.size()) {
                    checked.set(idx, !checked.get(idx));
                    return true;
                }
            }
            return super.mouseClicked(mx, my, btn);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
            scroll = Math.max(0, Math.min(scroll - (int) vAmt, Math.max(0, allPacks.size() - visibleRows())));
            return true;
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            // Dark background
            ctx.fill(0, 0, width, height, 0xCC000000);

            // Title
            ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);

            // Subtitle
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§7O = open menu   P = toggle on/off   Click rows to select"),
                    width / 2, 25, 0x888888);

            // Selected count
            long selectedCount = checked.stream().filter(b -> b).count();
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§a" + selectedCount + " §7packs selected"),
                    width / 2, 40, 0xFFFFFF);

            // Pack list
            int y = TOP;
            for (int i = scroll; i < Math.min(scroll + visibleRows(), allPacks.size()); i++) {
                boolean hover = mx >= 15 && mx <= width - 15 && my >= y && my < y + ROW - 1;
                boolean sel = checked.get(i);

                int bg = sel ? 0x8800BB00 : (hover ? 0x44FFFFFF : 0x44000000);
                ctx.fill(15, y, width - 15, y + ROW - 2, bg);

                // Checkbox
                ctx.drawTextWithShadow(textRenderer, Text.literal(sel ? "§a[✔]" : "§8[  ]"), 20, y + 6, 0xFFFFFF);

                // Pack name
                String name = allPacks.get(i);
                String display = name.length() > 55 ? name.substring(0, 52) + "..." : name;
                ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + display), 55, y + 6, 0xFFFFFF);

                y += ROW;
            }

            // Scroll hint
            if (allPacks.size() > visibleRows()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("§8Scroll for more  (" + allPacks.size() + " total)"),
                        width / 2, height - 42, 0x666666);
            }

            super.render(ctx, mx, my, delta);
        }

        @Override
        public boolean shouldPause() { return false; }
    }
}
