package com.welfarinas.autoanvil;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AnvilMenu;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class AutoAnvilClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autoanvil", "main"));
    private static KeyMapping configKey;
    private static AnvilExecutor running;
    private static final long START_COOLDOWN_MS = 1000;
    private static long lastStartMillis;

    @Override
    public void onInitializeClient() {
        AnvilConfig.get();
        configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.autoanvil.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(AutoAnvilClient::onTick);
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AnvilScreen)) return;
            // Encima del yunque (176x166), a la derecha.
            int left = (scaledWidth - 176) / 2;
            int top = (scaledHeight - 166) / 2;
            Screens.getButtons(screen).add(Button.builder(Component.literal("Auto-encantar"), b -> start(client))
                    .bounds(left + 176 - 100, top - 22, 100, 20)
                    .tooltip(Tooltip.create(Component.literal("Aplica los libros de la config a las piezas del inventario.")))
                    .build());
        });
    }

    private static void onTick(Minecraft mc) {
        while (configKey.consumeClick()) {
            if (mc.level != null && mc.screen == null) mc.setScreen(new ConfigScreen(null));
        }
        if (running != null) {
            running.tick(mc);
            if (running.isFinished()) running = null;
        }
    }

    private static void start(Minecraft mc) {
        // Evita repetir el informe si se pulsa varias veces seguidas o se mantiene Enter.
        long now = System.currentTimeMillis();
        if (now - lastStartMillis < START_COOLDOWN_MS) return;
        lastStartMillis = now;
        if (running != null) {
            say(Component.literal("Ya está en marcha.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (mc.player == null || !(mc.player.containerMenu instanceof AnvilMenu menu)) return;
        if (!menu.getCarried().isEmpty()) {
            say(Component.literal("Suelta el objeto del cursor.").withStyle(ChatFormatting.RED));
            return;
        }
        for (int slot = 0; slot <= AnvilMenu.RESULT_SLOT; slot++) {
            if (menu.getSlot(slot).hasItem()) {
                say(Component.literal("Vacía el yunque.").withStyle(ChatFormatting.RED));
                return;
            }
        }

        AnvilConfig config = AnvilConfig.get();
        AnvilPlanner.Result result = AnvilPlanner.plan(mc.player, AnvilPlanner.fromMenu(menu), config);
        List<AnvilPlanner.Count> missing = result.missing();
        if (result.plan() == null && (!result.problems().isEmpty() || !missing.isEmpty())) {
            say(Component.literal("No se puede empezar:").withStyle(ChatFormatting.RED));
            for (AnvilPlanner.Count c : missing) say(Component.literal(" - ").append(c.formatWithMissing()));
            result.problems().forEach(c -> say(Component.literal(" - ").append(c).withStyle(ChatFormatting.RED)));
        }
        result.info().forEach(c -> say(c.copy().withStyle(ChatFormatting.GRAY)));
        if (result.plan() == null) return;

        result.warnings().forEach(c -> say(c.copy().withStyle(ChatFormatting.YELLOW)));
        for (AnvilPlanner.Count c : missing) say(Component.literal(" - ").append(c.formatWithMissing()));

        AnvilPlanner.Plan plan = result.plan();
        int steps = plan.pieces().stream().mapToInt(p -> p.steps().size()).sum();
        say(Component.literal("Modo " + (config.combineBooks ? "combinar libros" : "simple") + ": "
                + plan.pieces().size() + " unidades, " + steps + " pasos.").withStyle(ChatFormatting.AQUA));
        if (!result.creative()) say(AnvilPlanner.describeXp(plan.pieces(), mc.player.experienceLevel));
        for (Piece piece : Piece.values()) {
            List<AnvilPlanner.PiecePlan> units = plan.pieces().stream().filter(u -> u.piece() == piece).toList();
            if (units.isEmpty()) continue;
            say(Component.literal(piece.fullName() + (units.size() > 1 ? " x" + units.size() : "") + ":")
                    .withStyle(ChatFormatting.WHITE));
            AnvilPlanner.describeSteps(units, result.creative()).forEach(line -> say(Component.literal("  ").append(line)));
        }
        running = new AnvilExecutor(plan, menu.containerId, config.clickTicks(), config.waitForXp);
    }

    static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal("[AutoAnvil] ").withStyle(ChatFormatting.GOLD)
                .append(message), false);
    }
}
