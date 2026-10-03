package com.welfarinas.autoanvil;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.enchantment.Enchantment;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Elige, por pieza, qué encantamientos, a qué nivel y cuántas unidades. Se guarda en config/autoanvil.json. */
public class ConfigScreen extends Screen {
    private static final int TAB_WIDTH = 64;
    private static final int CELL_WIDTH = 130;
    private static final int GAP = 4;
    private static final int ROW_HEIGHT = 22;
    private static final int GRID_TOP = 72;

    private final Screen parent;
    private final AnvilConfig working = AnvilConfig.get().copy();
    private Piece selected = Piece.HELMET;
    private Component status = Component.empty();
    private boolean noWorld;
    private static final Component COUNT_LABEL = Component.literal("Cantidad:");
    private static final String ALL_LABEL = "Todas las del inventario";
    private static final int COUNTER_REFRESH_TICKS = 10;
    private int ticksSinceRefresh;
    private CountBox countBox;
    private Button minusButton;
    private Button plusButton;
    private int countLabelX;
    private int countLabelWidth;
    /** Evita que el responder reaccione a los cambios de texto hechos por código. */
    private boolean updatingCountBox;
    private int gridBottom = GRID_TOP;
    private List<Component> counterLines = List.of();

    public ConfigScreen(Screen parent) {
        super(Component.literal("AutoAnvil - Encantamientos"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Pestañas de piezas: ancho según el nombre más largo (sin marcas añadidas que se corten).
        // Tachada = "Activa: No"; gris = sin encantamientos elegidos.
        int tabWidth = TAB_WIDTH;
        for (Piece piece : Piece.values()) tabWidth = Math.max(tabWidth, font.width(piece.label) + 12);
        int tabsWidth = Piece.values().length * (tabWidth + GAP) - GAP;
        int x = (width - tabsWidth) / 2;
        for (Piece piece : Piece.values()) {
            AnvilConfig.PieceConfig pc = working.piece(piece);
            MutableComponent tabLabel = Component.literal(piece.label);
            if (!pc.enabled) tabLabel.withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.GRAY);
            else if (pc.enchantments.isEmpty()) tabLabel.withStyle(ChatFormatting.GRAY);
            String state = !pc.enabled ? "Activa: No" : pc.enchantments.isEmpty() ? "Sin encantamientos" : "Activa";
            Button tab = addRenderableWidget(Button.builder(tabLabel, b -> {
                selected = piece;
                status = Component.empty();
                rebuildWidgets();
            }).bounds(x, 22, tabWidth, 20)
                    .tooltip(Tooltip.create(Component.literal(piece.fullName() + " — " + state)))
                    .build());
            tab.active = piece != selected;
            x += tabWidth + GAP;
        }

        // Opciones de la pieza: Activa | Usar cantidad | [Cantidad: - campo +] | Quitar todos.
        // "Activa" y "Usar cantidad" son independientes: cada botón solo cambia su propio valor.
        AnvilConfig.PieceConfig pc = working.piece(selected);
        boolean useCount = Boolean.TRUE.equals(pc.useCount);
        countLabelWidth = font.width(COUNT_LABEL);
        // El hueco de la cantidad muestra el campo (Sí) o "Todas las del inventario / (N detectadas)" en dos líneas (No).
        int countGroupWidth = Math.max(countLabelWidth + 2 + 20 + 2 + 30 + 2 + 20,
                Math.max(font.width(ALL_LABEL), font.width(detectedLabel(AnvilConfig.MAX_COUNT))));
        int rowWidth = 70 + GAP + 110 + GAP + countGroupWidth + GAP + 80;
        x = (width - rowWidth) / 2;
        addRenderableWidget(Button.builder(Component.literal("Activa: " + (pc.enabled ? "Sí" : "No")), b -> {
            pc.enabled = !pc.enabled;
            rebuildWidgets();
        }).bounds(x, 46, 70, 20)
                .tooltip(Tooltip.create(Component.literal("Si el yunque encanta esta pieza o la ignora. "
                        + "No cambia la cantidad ni el contador de esta pestaña.")))
                .build());
        x += 70 + GAP;
        addRenderableWidget(Button.builder(Component.literal("Usar cantidad: " + (useCount ? "Sí" : "No")), b -> {
            setUseCount(selected, !Boolean.TRUE.equals(working.piece(selected).useCount));
            rebuildWidgets();
        }).bounds(x, 46, 110, 20)
                .tooltip(Tooltip.create(Component.literal("No: se encantan todas las piezas de este tipo que lleves "
                        + "en el inventario (las 36 casillas; la armadura puesta no cuenta).\n"
                        + "Sí: se encantan exactamente las unidades del campo Cantidad (1-" + AnvilConfig.MAX_COUNT
                        + "); si llevas menos, avisa de cuántas faltan.\nNo cambia \"Activa\".")))
                .build());
        x += 110 + GAP;

        // Cantidad: [-] [campo] [+], solo con "Usar cantidad: Sí". Shift + clic = ±5, rueda = ±1.
        countLabelX = x;
        countBox = null;
        minusButton = null;
        plusButton = null;
        if (useCount) {
            int cx = x + countLabelWidth + 2;
            Tooltip countTip = Tooltip.create(Component.literal("Cuántas unidades encantar (" + AnvilConfig.MIN_COUNT + "-"
                    + AnvilConfig.MAX_COUNT + "). Escribe el número, usa - y + (Shift + clic: ±5) o la rueda (±1)."));
            minusButton = addRenderableWidget(Button.builder(Component.literal("-"), b -> stepCount(-1))
                    .bounds(cx, 46, 20, 20).tooltip(countTip).build());
            cx += 20 + 2;
            countBox = new CountBox(font, cx + 1, 47, 28, 18, selected);
            countBox.setMaxLength(2);
            countBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
            countBox.setValue(String.valueOf(pc.count));
            countBox.setResponder(this::onCountTyped);
            countBox.setTooltip(countTip);
            addRenderableWidget(countBox);
            cx += 30 + 2;
            plusButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> stepCount(1))
                    .bounds(cx, 46, 20, 20).tooltip(countTip).build());
        }
        x += countGroupWidth + GAP;

        addRenderableWidget(Button.builder(Component.literal("Quitar todos"), b -> {
            // Solo quita encantamientos y pone la cantidad en 1; no toca "Activa" ni "Usar cantidad".
            pc.enchantments.clear();
            applyCount(selected, AnvilConfig.MIN_COUNT);
            status = Component.empty();
            rebuildWidgets();
        }).bounds(x, 46, 80, 20)
                .tooltip(Tooltip.create(Component.literal("Quita los encantamientos de esta pieza y pone la cantidad "
                        + "en 1. No cambia \"Activa\" ni \"Usar cantidad\".")))
                .build());

        // Cuadrícula de encantamientos aplicables a la pieza.
        List<Holder.Reference<Enchantment>> available = availableEnchantments();
        noWorld = available == null;
        gridBottom = GRID_TOP;
        if (available != null) {
            int cols = width >= 3 * (CELL_WIDTH + GAP) + 20 ? 3 : 2;
            int gridWidth = cols * (CELL_WIDTH + GAP) - GAP;
            int x0 = (width - gridWidth) / 2;
            for (int i = 0; i < available.size(); i++) {
                Holder.Reference<Enchantment> holder = available.get(i);
                int cx = x0 + (i % cols) * (CELL_WIDTH + GAP);
                int cy = GRID_TOP + (i / cols) * ROW_HEIGHT;
                addRenderableWidget(Button.builder(cellLabel(holder, pc, !working.combineBooks), b -> cycle(holder, pc))
                        .bounds(cx, cy, CELL_WIDTH, 20)
                        .tooltip(Tooltip.create(Component.empty().append(holder.value().description())
                                .withStyle(ChatFormatting.YELLOW)
                                .append(Component.literal("\nClic: subir nivel (tras el máximo vuelve a —)."
                                        + (working.combineBooks ? "" : " El número es el orden en modo simple: para"
                                        + " mandar uno al final, quítalo y vuelve a elegirlo."))
                                        .withStyle(ChatFormatting.WHITE))))
                        .build());
            }
            gridBottom = GRID_TOP + (available.size() + cols - 1) / cols * ROW_HEIGHT;
        }
        counterLines = buildCounters(pc);

        // Combinar libros / retardo / guardar / cancelar.
        int bottom = height - 26;
        x = width / 2 - (130 + 100 + 2 * 80 + 3 * GAP) / 2;
        addRenderableWidget(Button.builder(Component.literal("Combinar libros: " + (working.combineBooks ? "Sí" : "No")), b -> {
            working.combineBooks = !working.combineBooks;
            rebuildWidgets();
        }).bounds(x, bottom, 130, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Sí: busca el orden más barato en niveles. Puede juntar libros entre sí antes de "
                        + "aplicarlos (p. ej. Irrompibilidad + Reparación) y nunca pasa de 39 niveles por paso.\n\n"
                        + "No (modo simple): pieza + primer libro, el resultado + el siguiente... en el orden de la "
                        + "lista (el número de cada encantamiento). Nunca junta libros; suele costar más y, con muchos "
                        + "libros, algún paso puede pasar de 39 niveles.")))
                .build());
        x += 130 + GAP;
        addRenderableWidget(Button.builder(Component.literal("Retardo: " + working.clickDelayTicks + " ticks"), b -> {
            working.clickDelayTicks = working.clickDelayTicks >= AnvilConfig.MAX_DELAY
                    ? AnvilConfig.MIN_DELAY : working.clickDelayTicks + 1;
            rebuildWidgets();
        }).bounds(x, bottom, 100, 20)
                .tooltip(Tooltip.create(Component.literal("Ticks entre clics en el yunque (1 tick = 50 ms). "
                        + "Ahora: " + working.clickDelayTicks * 50 + " ms.")))
                .build());
        x += 100 + GAP;
        addRenderableWidget(Button.builder(Component.literal("Guardar"), b -> {
            normalizeCount();
            AnvilConfig.set(working);
            onClose();
        }).bounds(x, bottom, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                .bounds(x + 80 + GAP, bottom, 80, 20).build());
    }

    /** "(N detectadas)" para "Usar cantidad: No". */
    private static String detectedLabel(int detected) {
        return "(" + detected + (detected == 1 ? " detectada)" : " detectadas)");
    }

    /** Piezas de ese tipo en las 36 casillas del inventario (la armadura puesta no cuenta). */
    private int countInInventory(Piece piece) {
        if (minecraft == null || minecraft.player == null) return 0;
        int n = 0;
        for (var stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (stack.is(piece.item)) n++;
        }
        return n;
    }

    /** El contador sigue al inventario aunque la pantalla esté abierta (p. ej. en multijugador). */
    @Override
    public void tick() {
        super.tick();
        if (++ticksSinceRefresh >= COUNTER_REFRESH_TICKS) {
            ticksSinceRefresh = 0;
            counterLines = buildCounters(working.piece(selected));
        }
    }

    /**
     * Líneas bajo la cuadrícula. "Activa" no influye en nada de esto.
     * - Contador de ESTA pieza (piezas, libros y XP solo para ella), con la cantidad del campo (Usar cantidad: Sí)
     *   o con todas las del inventario (No), y avisos como "no hay ninguna pieza".
     * - "Total de todas las piezas" en otra línea si hay más piezas activas.
     * - Los pasos de yunque y su coste.
     */
    private List<Component> buildCounters(AnvilConfig.PieceConfig pc) {
        if (minecraft == null || minecraft.player == null) return List.of();
        List<AnvilPlanner.InvSlot> inventory = AnvilPlanner.fromInventory(minecraft.player.getInventory());

        // Solo esta pieza (activa a efectos del cálculo), para no mezclar necesidades de otras.
        AnvilConfig solo = working.copy();
        for (Piece piece : Piece.values()) solo.piece(piece).enabled = piece == selected;
        AnvilPlanner.Result own = AnvilPlanner.plan(minecraft.player, inventory, solo);
        AnvilPlanner.Stock stock = own.stock().get(selected);

        // Contador de la pieza: siempre visible, con "Usar cantidad" en Sí o en No.
        MutableComponent line = Component.empty().append(pieceCounter(pc, stock));
        for (AnvilPlanner.Count c : own.counts()) {
            if (c.kind() == AnvilPlanner.Kind.PIECE) continue; // ya va en pieceCounter
            line.append("   ").append(c.formatWithMissing());
        }
        if (pc.enchantments.isEmpty()) {
            line.append(Component.literal("   Sin encantamientos elegidos: no hacen falta libros ni XP.")
                    .withStyle(ChatFormatting.GRAY));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(line);
        // Avisos (encantamientos incompatibles, demasiado caro, XP...). El de "no hay ninguna" ya está en el contador.
        Component none = stock == null ? null : stock.noneProblem();
        own.problems().stream().filter(p -> p != none)
                .forEach(p -> lines.add(Component.empty().append(p).withStyle(ChatFormatting.RED)));

        boolean othersActive = false;
        for (Piece piece : Piece.values()) {
            AnvilConfig.PieceConfig other = working.piece(piece);
            if (piece != selected && other.enabled && !other.enchantments.isEmpty()) othersActive = true;
        }
        if (othersActive) lines.add(totalLine(AnvilPlanner.plan(minecraft.player, inventory, working)));

        List<AnvilPlanner.PiecePlan> units = own.unitsOf(selected);
        if (!units.isEmpty()) {
            List<Component> stepLines = AnvilPlanner.describeSteps(units, own.creative());
            lines.add(Component.literal(working.combineBooks ? "Pasos (Combinar libros): " : "Pasos (simple): ")
                    .withStyle(ChatFormatting.AQUA).append(stepLines.getFirst()));
            lines.addAll(stepLines.subList(1, stepLines.size()));
        }
        return lines;
    }

    /**
     * "Cascos: 1/3 ✘ (faltan 2)" con Usar cantidad: Sí, o "Cascos: 3/3 ✔" con No (todas las detectadas que
     * necesitan algo). {@code stock} es null si la pieza no tiene encantamientos elegidos.
     */
    private Component pieceCounter(AnvilConfig.PieceConfig pc, AnvilPlanner.Stock stock) {
        int detected = countInInventory(selected);
        int usable = stock != null ? stock.usable() : detected;
        Component name = Component.literal(selected.plural);
        if (Boolean.TRUE.equals(pc.useCount)) {
            return new AnvilPlanner.Count(AnvilPlanner.Kind.PIECE, selected.key, name,
                    Math.min(usable, pc.count), pc.count).formatWithMissing();
        }
        if (detected == 0) {
            return Component.empty().append(name).append(": ")
                    .append(Component.literal("0 detectadas ✘ (no hay ninguna en el inventario)")
                            .withStyle(ChatFormatting.RED));
        }
        MutableComponent c = Component.empty().append(new AnvilPlanner.Count(AnvilPlanner.Kind.PIECE, selected.key,
                name, usable, usable).format());
        if (stock != null && stock.alreadyDone() + stock.incompatible() > 0) {
            StringBuilder extra = new StringBuilder(" (");
            if (stock.alreadyDone() > 0) extra.append(stock.alreadyDone()).append(" ya completas");
            if (stock.alreadyDone() > 0 && stock.incompatible() > 0) extra.append(", ");
            if (stock.incompatible() > 0) extra.append(stock.incompatible()).append(" incompatibles");
            c.append(Component.literal(extra + ")").withStyle(ChatFormatting.GRAY));
        }
        return c;
    }

    /** "Total de todas las piezas: ..." con lo que falta (o "todo listo") y la XP de todas las piezas activas. */
    private static Component totalLine(AnvilPlanner.Result all) {
        MutableComponent line = Component.literal("Total de todas las piezas: ").withStyle(ChatFormatting.GOLD);
        List<AnvilPlanner.Count> missing = all.missing().stream()
                .filter(c -> c.kind() != AnvilPlanner.Kind.XP).toList();
        if (missing.isEmpty()) {
            line.append(Component.literal("piezas y libros ✔").withStyle(ChatFormatting.GREEN));
        } else {
            line.append(Component.literal("faltan ").withStyle(ChatFormatting.RED));
            for (int i = 0; i < missing.size(); i++) {
                AnvilPlanner.Count c = missing.get(i);
                if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.RED));
                line.append(Component.literal((c.need() - c.have()) + "× ").withStyle(ChatFormatting.RED))
                        .append(c.name());
            }
        }
        all.counts().stream().filter(c -> c.kind() == AnvilPlanner.Kind.XP).findFirst()
                .ifPresent(xp -> line.append("   ").append(xp.formatWithMissing()));
        if (!all.problems().isEmpty()) {
            int n = all.problems().size();
            line.append(Component.literal("   + " + n + (n == 1 ? " aviso" : " avisos") + " (míralos en cada pestaña)")
                    .withStyle(ChatFormatting.RED));
        }
        return line;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean overCount = (countBox != null && countBox.isMouseOver(mouseX, mouseY))
                || (minusButton != null && minusButton.isMouseOver(mouseX, mouseY))
                || (plusButton != null && plusButton.isMouseOver(mouseX, mouseY));
        if (overCount && scrollY != 0) {
            setCount(working.piece(selected).count + (scrollY > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Botones - y +: ±1, o ±5 con Shift. */
    private void stepCount(int direction) {
        boolean shift = InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
        setCount(working.piece(selected).count + direction * (shift ? 5 : 1));
    }

    /** Fija la cantidad (ajustada a 1-36), actualiza el campo y la guarda. */
    private void setCount(int count) {
        int clamped = Math.clamp(count, AnvilConfig.MIN_COUNT, AnvilConfig.MAX_COUNT);
        setCountBoxText(clamped);
        applyCount(selected, clamped);
    }

    private void onCountTyped(String text) {
        if (updatingCountBox || text.isEmpty()) return; // vacío: se corrige al salir del campo
        int typed = Integer.parseInt(text);
        int clamped = Math.clamp(typed, AnvilConfig.MIN_COUNT, AnvilConfig.MAX_COUNT);
        if (!text.equals(String.valueOf(clamped))) setCountBoxText(clamped);
        applyCount(selected, clamped);
    }

    /** Al salir del campo o pulsar Enter: si está vacío pasa al valor válido más cercano (1). */
    private void normalizeCount() {
        if (countBox != null) countBox.normalize();
    }

    private void setCountBoxText(int count) {
        if (countBox == null) return;
        updatingCountBox = true;
        countBox.setValue(String.valueOf(count));
        updatingCountBox = false;
    }

    /** "Usar cantidad" se guarda al momento, igual que la cantidad. No toca "Activa" ni la cantidad. */
    private void setUseCount(Piece piece, boolean useCount) {
        working.piece(piece).useCount = useCount;
        AnvilConfig live = AnvilConfig.get();
        live.piece(piece).useCount = useCount;
        live.save();
    }

    /** La cantidad se guarda en el JSON al momento (aunque luego se pulse Cancelar) y refresca el contador. */
    private void applyCount(Piece piece, int count) {
        working.piece(piece).count = count;
        AnvilConfig live = AnvilConfig.get();
        live.piece(piece).count = count;
        live.save();
        counterLines = buildCounters(working.piece(selected));
    }

    /** Campo numérico de la cantidad: corrige el valor al perder el foco o con Enter; flechas = ±1. */
    private final class CountBox extends EditBox {
        /** Pieza de la pestaña en la que se creó (el foco puede perderse después de cambiar de pestaña). */
        private final Piece piece;

        CountBox(Font font, int x, int y, int width, int height, Piece piece) {
            super(font, x, y, width, height, Component.literal("Cantidad"));
            this.piece = piece;
        }

        /** Vacío -> valor válido más cercano (1). */
        void normalize() {
            if (!getValue().isEmpty()) return;
            if (this == countBox) setCountBoxText(AnvilConfig.MIN_COUNT);
            applyCount(piece, AnvilConfig.MIN_COUNT);
        }

        @Override
        public void setFocused(boolean focused) {
            boolean wasFocused = isFocused();
            super.setFocused(focused);
            if (wasFocused && !focused) normalize();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isConfirmation()) {
                normalize();
                return true;
            }
            if (event.isUp() || event.isDown()) {
                setCount(working.piece(selected).count + (event.isUp() ? 1 : -1));
                return true;
            }
            return super.keyPressed(event);
        }
    }

    private List<Holder.Reference<Enchantment>> availableEnchantments() {
        if (minecraft == null || minecraft.level == null) return null;
        Registry<Enchantment> registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return registry.listElements()
                .filter(h -> !h.is(EnchantmentTags.CURSE))
                .filter(h -> h.value().canEnchant(selected.stack()))
                .sorted(Comparator.comparing(h -> h.value().description().getString()))
                .toList();
    }

    private static String id(Holder.Reference<Enchantment> holder) {
        return holder.key().identifier().toString();
    }

    /** "1. Prot. contra fuego: IV": el nombre se abrevia para que la etiqueta quepa entera en el botón. */
    private Component cellLabel(Holder.Reference<Enchantment> holder, AnvilConfig.PieceConfig pc, boolean showOrder) {
        int level = pc.enchantments.getOrDefault(id(holder), 0);
        int max = holder.value().getMaxLevel();
        MutableComponent value;
        if (level <= 0) value = Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        else if (max == 1) value = Component.literal("Sí").withStyle(ChatFormatting.GREEN);
        else value = Component.translatable("enchantment.level." + level).withStyle(ChatFormatting.GREEN);
        MutableComponent label = Component.empty();
        if (showOrder && level > 0) {
            int order = new ArrayList<>(pc.enchantments.keySet()).indexOf(id(holder)) + 1;
            label.append(Component.literal(order + ". ").withStyle(ChatFormatting.AQUA));
        }
        int room = CELL_WIDTH - 8 - font.width(label) - font.width(": ") - font.width(value);
        String name = fitName(holder.value().description().getString(), room);
        return label.append(name).append(": ").append(value);
    }

    private static final Set<String> STOP_WORDS = Set.of("el", "la", "los", "las", "de", "del", "the", "of");

    /**
     * Acorta un nombre hasta que quepa en {@code maxWidth} píxeles: quita artículos, abrevia las palabras más
     * largas ("Protección" -> "Prot.") y, si aún no cabe, recorta con "…".
     */
    private String fitName(String name, int maxWidth) {
        if (font.width(name) <= maxWidth) return name;
        List<String> words = new ArrayList<>(List.of(name.split(" ")));
        if (words.size() > 1) words.removeIf(w -> STOP_WORDS.contains(w.toLowerCase()));
        String joined = String.join(" ", words);
        while (font.width(joined) > maxWidth) {
            int longest = -1;
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (w.length() > 5 && !w.endsWith(".") && (longest < 0 || w.length() > words.get(longest).length())) {
                    longest = i;
                }
            }
            if (longest < 0) break;
            words.set(longest, words.get(longest).substring(0, 4) + ".");
            joined = String.join(" ", words);
        }
        if (font.width(joined) <= maxWidth) return joined;
        while (joined.length() > 1 && font.width(joined + "…") > maxWidth) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.strip() + "…";
    }

    private void cycle(Holder.Reference<Enchantment> holder, AnvilConfig.PieceConfig pc) {
        String id = id(holder);
        int next = pc.enchantments.getOrDefault(id, 0) + 1;
        if (next > holder.value().getMaxLevel()) {
            pc.enchantments.remove(id);
        } else {
            pc.enchantments.put(id, next);
            status = Component.empty();
            removeIncompatible(holder, pc);
        }
        rebuildWidgets();
    }

    /** Al elegir un encantamiento se quitan los incompatibles (p. ej. Protección vs Protección contra explosiones). */
    private void removeIncompatible(Holder.Reference<Enchantment> chosen, AnvilConfig.PieceConfig pc) {
        if (minecraft == null || minecraft.level == null) return;
        Registry<Enchantment> registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        MutableComponent removed = null;
        Iterator<Map.Entry<String, Integer>> it = pc.enchantments.entrySet().iterator();
        while (it.hasNext()) {
            String otherId = it.next().getKey();
            Identifier parsed = Identifier.tryParse(otherId);
            Optional<Holder.Reference<Enchantment>> other = parsed == null ? Optional.empty() : registry.get(parsed);
            if (other.isEmpty() || other.get().equals(chosen)) continue;
            if (!Enchantment.areCompatible(chosen, other.get())) {
                it.remove();
                removed = removed == null ? Component.literal("Quitado por incompatible: ") : removed.append(", ");
                removed.append(other.get().value().description());
            }
        }
        if (removed != null) status = removed.withStyle(ChatFormatting.YELLOW);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        if (countBox != null) {
            graphics.drawString(font, COUNT_LABEL, countLabelX, 52, 0xFFFFFFFF);
        } else {
            // "Usar cantidad: No": se usan todas las del inventario; el número se actualiza en vivo.
            graphics.drawString(font, ALL_LABEL, countLabelX, 46, 0xFFFFFFFF);
            graphics.drawString(font, detectedLabel(countInInventory(selected)), countLabelX, 57, 0xFFAAAAAA);
        }
        if (noWorld) {
            graphics.drawCenteredString(font, Component.literal("Entra en un mundo para ver los encantamientos."),
                    width / 2, GRID_TOP + 6, 0xFFFF5555);
        }
        boolean hasStatus = !status.getString().isEmpty();
        int maxY = height - 28 - (hasStatus ? 12 : 0) - font.lineHeight;
        int y = gridBottom + 4;
        outer:
        for (Component line : counterLines) {
            for (FormattedCharSequence part : font.split(line, width - 20)) {
                if (y > maxY) break outer;
                graphics.drawCenteredString(font, part, width / 2, y, 0xFFFFFFFF);
                y += font.lineHeight + 1;
            }
        }
        if (hasStatus) graphics.drawCenteredString(font, status, width / 2, height - 38, 0xFFFFFF55);
    }

    @Override
    public void onClose() {
        normalizeCount();
        minecraft.setScreen(parent);
    }
}
