package com.welfarinas.autoanvil;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ejecuta un plan solo con Shift + clic, sin usar el cursor. Desde el inventario, el objeto va a la primera
 * ranura libre del yunque: por eso se pone primero el de la izquierda y luego el de la derecha.
 */
final class AnvilExecutor {
    private static final int RESULT_TIMEOUT_TICKS = 60;

    private enum Phase { PLACE_LEFT, CHECK_LEFT, CHECK_RIGHT, WAIT_RESULT, TAKE_RESULT, LOCATE_RESULT }

    private final AnvilPlanner.Plan plan;
    private final int containerId;
    /** Ticks sin hacer nada tras cada clic. */
    private final int delay;
    private final boolean waitForXp;
    /** Nodo del plan -> casilla del menú donde está ahora. */
    private final Map<Integer, Integer> slotOf;

    private int pieceIndex;
    private int stepIndex;
    private Phase phase = Phase.PLACE_LEFT;
    private int cooldown;
    private int waitTicks;
    private ItemStack moved = ItemStack.EMPTY;
    private List<Integer> emptyBeforeTake = List.of();
    private int done;
    private boolean waitingXp;
    private boolean finished;

    AnvilExecutor(AnvilPlanner.Plan plan, int containerId, int clickTicks, boolean waitForXp) {
        this.plan = plan;
        this.containerId = containerId;
        this.delay = clickTicks - 1;
        this.waitForXp = waitForXp;
        this.slotOf = new HashMap<>(plan.initialSlots());
    }

    boolean isFinished() {
        return finished;
    }

    void tick(Minecraft mc) {
        if (finished) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || !(mc.screen instanceof AnvilScreen)
                || !(player.containerMenu instanceof AnvilMenu menu) || menu.containerId != containerId) {
            finish(Component.literal("Yunque cerrado, cancelado. " + progress()).withStyle(ChatFormatting.RED));
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        AnvilPlanner.PiecePlan unit = plan.pieces().get(pieceIndex);
        AnvilPlanner.Step step = unit.steps().get(stepIndex);
        switch (phase) {
            case PLACE_LEFT -> {
                // La XP se mira antes de poner nada, así el yunque queda libre mientras espera.
                if (!hasXp(mc, menu, player, step.cost(), unit, step)) return;
                if (menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem() || menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()) {
                    stop(mc, menu, "El yunque no está vacío. " + progress());
                    return;
                }
                place(mc, menu, step.left(), Phase.CHECK_LEFT);
            }
            // La comprobación va en el mismo tick que la acción siguiente, para no alargar el retardo.
            case CHECK_LEFT -> {
                if (check(mc, menu, step.left(), AnvilMenu.INPUT_SLOT, "izquierda", Phase.CHECK_RIGHT)) {
                    place(mc, menu, step.right(), Phase.CHECK_RIGHT);
                }
            }
            case CHECK_RIGHT -> {
                if (check(mc, menu, step.right(), AnvilMenu.ADDITIONAL_SLOT, "derecha", Phase.WAIT_RESULT)) {
                    waitResult(mc, menu, player, unit, step);
                }
            }
            case WAIT_RESULT -> waitResult(mc, menu, player, unit, step);
            case TAKE_RESULT -> {
                emptyBeforeTake = emptySlots(menu);
                if (emptyBeforeTake.isEmpty()) {
                    stop(mc, menu, "Inventario lleno. " + progress());
                    return;
                }
                click(mc, menu, AnvilMenu.RESULT_SLOT);
                phase = Phase.LOCATE_RESULT;
                waitTicks = 0;
                cooldown = delay;
            }
            case LOCATE_RESULT -> locateResult(mc, menu, unit, step);
        }
    }

    private void place(Minecraft mc, AnvilMenu menu, int node, Phase next) {
        Integer slot = slotOf.get(node);
        if (slot == null || !menu.getSlot(slot).hasItem()) {
            stop(mc, menu, "Falta un objeto del plan. " + progress());
            return;
        }
        moved = menu.getSlot(slot).getItem().copy();
        click(mc, menu, slot);
        phase = next;
        cooldown = delay;
    }

    /** Tras el Shift + clic, el objeto tiene que estar en su ranura del yunque y haber salido del inventario. */
    private boolean check(Minecraft mc, AnvilMenu menu, int node, int anvilSlot, String side, Phase next) {
        ItemStack inAnvil = menu.getSlot(anvilSlot).getItem();
        if (!ItemStack.isSameItemSameComponents(inAnvil, moved) || menu.getSlot(slotOf.get(node)).hasItem()) {
            stop(mc, menu, moved.getHoverName().getString() + " no llegó a la ranura " + side + ". " + progress());
            return false;
        }
        slotOf.remove(node);
        phase = next;
        waitTicks = 0;
        return true;
    }

    private void waitResult(Minecraft mc, AnvilMenu menu, LocalPlayer player, AnvilPlanner.PiecePlan unit,
                            AnvilPlanner.Step step) {
        waitTicks++;
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        int cost = menu.getCost();
        // Al menos 2 ticks, para que llegue el coste del servidor.
        if (result.isEmpty() || cost <= 0 || waitTicks < 2) {
            if (waitTicks > RESULT_TIMEOUT_TICKS) stop(mc, menu, "El yunque no da resultado. " + progress());
            return;
        }
        // Solo si el coste real supera al previsto. Si se cierra el yunque, el juego devuelve los objetos.
        if (!hasXp(mc, menu, player, cost, unit, step)) return;
        if (cost != step.cost()) {
            AutoAnvilClient.say(Component.literal("Este paso cuesta " + cost + " niveles, no " + step.cost() + ".")
                    .withStyle(ChatFormatting.YELLOW));
        }
        phase = Phase.TAKE_RESULT;
    }

    private void locateResult(Minecraft mc, AnvilMenu menu, AnvilPlanner.PiecePlan unit, AnvilPlanner.Step step) {
        Item expected = step.resultIsBook() ? Items.ENCHANTED_BOOK : unit.piece().item;
        Integer found = null;
        if (!menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
            for (int slot : emptyBeforeTake) {
                if (menu.getSlot(slot).getItem().is(expected)) {
                    found = slot;
                    break;
                }
            }
        }
        if (found == null) {
            if (++waitTicks > RESULT_TIMEOUT_TICKS) stop(mc, menu, "El resultado no llegó al inventario. " + progress());
            return;
        }
        slotOf.put(step.result(), found);
        advance(unit);
    }

    private void advance(AnvilPlanner.PiecePlan unit) {
        // Sin cooldown: ya se esperó después de recoger el resultado.
        phase = Phase.PLACE_LEFT;
        if (++stepIndex < unit.steps().size()) return;

        done++;
        stepIndex = 0;
        AutoAnvilClient.say(Component.literal(name(unit) + " lista, " + unit.totalCost() + " niveles. "
                + done + "/" + plan.pieces().size()).withStyle(ChatFormatting.GREEN));
        if (++pieceIndex >= plan.pieces().size()) {
            finish(Component.literal("Terminado: " + done + " encantadas." + booksNote()).withStyle(ChatFormatting.GREEN));
        }
    }

    /** Cada paso solo exige su propio coste. Sin niveles espera (Esperar XP: Sí) o se detiene. */
    private boolean hasXp(Minecraft mc, AnvilMenu menu, LocalPlayer player, int need, AnvilPlanner.PiecePlan unit,
                          AnvilPlanner.Step step) {
        int level = player.experienceLevel;
        if (player.hasInfiniteMaterials() || need <= level) {
            if (waitingXp) {
                waitingXp = false;
                AutoAnvilClient.say(Component.literal("XP suficiente, sigo.").withStyle(ChatFormatting.GREEN));
            }
            return true;
        }
        String label = step.label().getString();
        if (label.startsWith("+ ")) label = label.substring(2);
        String piece = unit.piece().label.toLowerCase(Locale.ROOT) + (unit.count() > 1 ? " " + unit.index() + "/" + unit.count() : "");
        String missing = label + " (" + piece + "). Necesita " + need + " niveles, tienes " + level + ".";
        if (!waitForXp) {
            stop(mc, menu, progress() + " Sin XP para " + missing + booksNote());
        } else if (!waitingXp) {
            waitingXp = true;
            AutoAnvilClient.say(Component.literal("Esperando XP: " + missing).withStyle(ChatFormatting.YELLOW));
        }
        return false;
    }

    private static String name(AnvilPlanner.PiecePlan unit) {
        return AnvilPlanner.unitName(unit.piece(), unit.index(), unit.count());
    }

    private String progress() {
        return "Encantadas " + done + " de " + plan.pieces().size() + ".";
    }

    private String booksNote() {
        int n = plan.withoutBooks();
        return n == 0 ? "" : " Faltan libros para " + n + (n == 1 ? " unidad más." : " unidades más.");
    }

    /** Devuelve al inventario lo que quede en el yunque y termina. */
    private void stop(Minecraft mc, AnvilMenu menu, String message) {
        for (int slot : new int[]{AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT}) {
            if (menu.getSlot(slot).hasItem()) click(mc, menu, slot);
        }
        if (menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem() || menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()) {
            message += " Quedan objetos en el yunque.";
        }
        if (!menu.getCarried().isEmpty()) message += " Hay un objeto en el cursor.";
        finish(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private void finish(Component message) {
        finished = true;
        AutoAnvilClient.say(message);
    }

    private static List<Integer> emptySlots(AnvilMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = AnvilPlanner.FIRST_INVENTORY_SLOT; slot < menu.slots.size(); slot++) {
            if (!menu.getSlot(slot).hasItem()) slots.add(slot);
        }
        return slots;
    }

    private static void click(Minecraft mc, AnvilMenu menu, int slot) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
    }
}
