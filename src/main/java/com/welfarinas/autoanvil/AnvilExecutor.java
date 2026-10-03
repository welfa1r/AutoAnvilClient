package com.welfarinas.autoanvil;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ejecuta un {@link AnvilPlanner.Plan} clic a clic, con un retardo configurable entre clics. */
final class AnvilExecutor {
    private static final int RESULT_TIMEOUT_TICKS = 60;

    private enum Phase { PICK_LEFT, DROP_LEFT, PICK_RIGHT, DROP_RIGHT, WAIT_RESULT, TAKE_RESULT, LOCATE_RESULT }

    private final AnvilPlanner.Plan plan;
    private final int containerId;
    private final int delay;
    /** Esperar XP: Sí -> sin niveles se queda esperando en vez de parar. */
    private final boolean waitForXp;
    /** Nodo del plan -> casilla del menú donde está ahora. */
    private final Map<Integer, Integer> slotOf;

    private int pieceIndex;
    private int stepIndex;
    private Phase phase = Phase.PICK_LEFT;
    private int cooldown;
    private int waitTicks;
    private List<Integer> emptyBeforeTake = List.of();
    private int piecesDone;
    private boolean waitingXp;
    private boolean finished;

    AnvilExecutor(AnvilPlanner.Plan plan, int containerId, int delay, boolean waitForXp) {
        this.plan = plan;
        this.containerId = containerId;
        this.delay = delay;
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
            finish(Component.literal("Se cerró el yunque: proceso cancelado. " + progress()).withStyle(ChatFormatting.RED));
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        AnvilPlanner.PiecePlan piecePlan = plan.pieces().get(pieceIndex);
        AnvilPlanner.Step step = piecePlan.steps().get(stepIndex);
        switch (phase) {
            case PICK_LEFT -> {
                // Antes de cada unidad: niveles para todos sus pasos. Dentro de la unidad: para el siguiente paso.
                boolean enoughXp = stepIndex == 0
                        ? hasXp(mc, menu, player, piecePlan.totalCost(), "la siguiente (" + label(piecePlan) + ")")
                        : hasXp(mc, menu, player, step.cost(), "el siguiente paso");
                if (!enoughXp) return;
                pick(mc, menu, step.left(), Phase.DROP_LEFT);
            }
            case DROP_LEFT -> drop(mc, menu, step.left(), AnvilMenu.INPUT_SLOT, Phase.PICK_RIGHT);
            case PICK_RIGHT -> pick(mc, menu, step.right(), Phase.DROP_RIGHT);
            case DROP_RIGHT -> drop(mc, menu, step.right(), AnvilMenu.ADDITIONAL_SLOT, Phase.WAIT_RESULT);
            case WAIT_RESULT -> waitResult(mc, menu, player, step);
            case TAKE_RESULT -> {
                emptyBeforeTake = emptyInventorySlots(menu);
                if (emptyBeforeTake.isEmpty()) {
                    stop(mc, menu, "Inventario lleno: no hay hueco para recoger el resultado.");
                    return;
                }
                click(mc, menu, AnvilMenu.RESULT_SLOT, ClickType.QUICK_MOVE);
                phase = Phase.LOCATE_RESULT;
                waitTicks = 0;
                cooldown = delay;
            }
            case LOCATE_RESULT -> locateResult(mc, menu, piecePlan, step);
        }
    }

    private void pick(Minecraft mc, AnvilMenu menu, int node, Phase next) {
        Integer slot = slotOf.get(node);
        if (slot == null || !menu.getSlot(slot).hasItem()) {
            stop(mc, menu, "No encuentro en el inventario un objeto que debía usar.");
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            stop(mc, menu, "El cursor tiene un objeto inesperado.");
            return;
        }
        click(mc, menu, slot, ClickType.PICKUP);
        phase = next;
        cooldown = delay;
    }

    private void drop(Minecraft mc, AnvilMenu menu, int node, int anvilSlot, Phase next) {
        if (menu.getCarried().isEmpty() || menu.getSlot(anvilSlot).hasItem()) {
            stop(mc, menu, "No se pudo colocar el objeto en el yunque.");
            return;
        }
        click(mc, menu, anvilSlot, ClickType.PICKUP);
        slotOf.remove(node);
        phase = next;
        waitTicks = 0;
        cooldown = delay;
    }

    private void waitResult(Minecraft mc, AnvilMenu menu, LocalPlayer player, AnvilPlanner.Step step) {
        waitTicks++;
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        int cost = menu.getCost();
        // Esperar al menos 2 ticks para que llegue el coste confirmado por el servidor.
        if (result.isEmpty() || cost <= 0 || waitTicks < 2) {
            if (waitTicks > RESULT_TIMEOUT_TICKS) {
                stop(mc, menu, "El yunque no da resultado (¿demasiado caro o bloqueado por el servidor?).");
            }
            return;
        }
        if (!hasXp(mc, menu, player, cost, "este paso")) return;
        if (cost != step.cost()) {
            AutoAnvilClient.say(Component.literal("Aviso: este paso cuesta " + cost + " niveles (previsto "
                    + step.cost() + ").").withStyle(ChatFormatting.YELLOW));
        }
        phase = Phase.TAKE_RESULT;
    }

    private void locateResult(Minecraft mc, AnvilMenu menu, AnvilPlanner.PiecePlan piecePlan, AnvilPlanner.Step step) {
        Item expected = step.resultIsBook() ? Items.ENCHANTED_BOOK : piecePlan.piece().item;
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
            if (++waitTicks > RESULT_TIMEOUT_TICKS) stop(mc, menu, "No se pudo recoger el resultado del yunque.");
            return;
        }
        slotOf.put(step.result(), found);
        advance(piecePlan);
    }

    private void advance(AnvilPlanner.PiecePlan piecePlan) {
        phase = Phase.PICK_LEFT;
        cooldown = delay;
        if (++stepIndex < piecePlan.steps().size()) return;

        piecesDone++;
        AutoAnvilClient.say(Component.literal("✔ " + label(piecePlan) + " lista ("
                + piecePlan.totalCost() + " niveles). Total: " + piecesDone + "/" + plan.pieces().size())
                .withStyle(ChatFormatting.GREEN));
        stepIndex = 0;
        if (++pieceIndex >= plan.pieces().size()) {
            finish(Component.literal("Todo encantado: " + piecesDone + " pieza(s)." + booksNote())
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * true si hay {@code need} niveles. Si no, con Esperar XP se queda esperando (avisa una vez y sigue sola
     * cuando los tenga); sin Esperar XP se detiene con el resumen.
     */
    private boolean hasXp(Minecraft mc, AnvilMenu menu, LocalPlayer player, int need, String what) {
        int level = player.experienceLevel;
        if (player.hasInfiniteMaterials() || need <= level) {
            if (waitingXp) {
                waitingXp = false;
                AutoAnvilClient.say(Component.literal("Ya tienes " + level + " niveles: continúa.").withStyle(ChatFormatting.GREEN));
            }
            return true;
        }
        String detail = "Para " + what + " faltan " + (need - level) + " niveles (tienes " + level + ", necesita " + need + ").";
        if (!waitForXp) {
            stopWithSummary(mc, menu, Component.literal(progress() + " " + detail + booksNote()).withStyle(ChatFormatting.RED));
        } else if (!waitingXp) {
            waitingXp = true;
            AutoAnvilClient.say(Component.literal("Esperando XP. " + progress() + " " + detail
                    + " Continúa en cuanto los tengas; cierra el yunque para cancelar.").withStyle(ChatFormatting.YELLOW));
        }
        return false;
    }

    private static String label(AnvilPlanner.PiecePlan p) {
        return p.piece().fullName() + (p.count() > 1 ? " " + p.index() + "/" + p.count() : "");
    }

    private String progress() {
        return "Encantadas " + piecesDone + " de " + plan.pieces().size() + ".";
    }

    /** " Faltan libros para otras N unidades." si se saltó alguna por falta de libros. */
    private String booksNote() {
        int n = plan.withoutBooks();
        return n == 0 ? "" : " Faltan libros para otra" + (n == 1 ? " unidad" : "s " + n + " unidades") + ".";
    }

    /** Se detiene, devuelve al inventario lo que pueda y dice cuántas piezas se hicieron. */
    private void stop(Minecraft mc, AnvilMenu menu, String reason) {
        stopWithSummary(mc, menu, Component.literal(reason + " Detenido. " + progress()).withStyle(ChatFormatting.RED));
    }

    private void stopWithSummary(Minecraft mc, AnvilMenu menu, Component summary) {
        // Devolver al inventario lo que quede en el cursor o en las casillas de entrada.
        if (!menu.getCarried().isEmpty()) {
            List<Integer> empty = emptyInventorySlots(menu);
            if (!empty.isEmpty()) click(mc, menu, empty.getFirst(), ClickType.PICKUP);
        }
        for (int slot : new int[]{AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT}) {
            if (menu.getSlot(slot).hasItem()) click(mc, menu, slot, ClickType.QUICK_MOVE);
        }
        boolean leftover = !menu.getCarried().isEmpty() || menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem()
                || menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem();
        MutableComponent message = summary.copy();
        if (leftover) {
            message.append(Component.literal(" Quedan objetos en el yunque o el cursor: haz hueco y recógelos.")
                    .withStyle(ChatFormatting.RED));
        }
        finish(message);
    }

    private void finish(Component message) {
        finished = true;
        AutoAnvilClient.say(message);
    }

    private static List<Integer> emptyInventorySlots(AnvilMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = AnvilPlanner.FIRST_INVENTORY_SLOT; slot < menu.slots.size(); slot++) {
            if (!menu.getSlot(slot).hasItem()) slots.add(slot);
        }
        return slots;
    }

    private static void click(Minecraft mc, AnvilMenu menu, int slot, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, mc.player);
    }
}
