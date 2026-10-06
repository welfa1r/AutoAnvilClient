package com.welfarinas.autoanvil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Busca el orden de yunque más barato para cada pieza. Usa la fórmula de AnvilMenu#createResult (1.21.11). */
final class AnvilPlanner {
    static final int TOO_EXPENSIVE = 40;
    static final int FIRST_INVENTORY_SLOT = AnvilMenu.RESULT_SLOT + 1;

    record Step(int left, int right, int result, int cost, boolean resultIsBook, Component label) {}

    record PiecePlan(Piece piece, int index, int count, List<Step> steps, int totalCost) {}

    /** withoutBooks: unidades que se saltan por falta de libros. */
    record Plan(List<PiecePlan> pieces, Map<Integer, Integer> initialSlots, int withoutBooks) {}

    enum Kind { PIECE, BOOK }

    record Count(Kind kind, Component name, int have, int need) {
        boolean ok() {
            return have >= need;
        }

        Component format() {
            return Component.empty().append(name).append(": ")
                    .append(Component.literal(have + "/" + need).withStyle(ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        Component formatWithMissing() {
            MutableComponent c = Component.empty().append(format());
            if (!ok()) c.append(Component.literal(" (faltan " + (need - have) + ")").withStyle(ChatFormatting.RED));
            return c;
        }
    }

    /**
     * plan es null si no se puede empezar. La XP no se comprueba aquí sino antes de cada paso.
     * units incluye las unidades a las que les falta algo, para mostrar sus pasos.
     */
    record Result(Plan plan, List<Count> counts, List<PiecePlan> units, List<Component> problems,
                  List<Component> warnings, List<Component> info, boolean creative, Map<Piece, Stock> stock) {
        List<Count> missing() {
            return counts.stream().filter(c -> !c.ok()).toList();
        }

        List<PiecePlan> unitsOf(Piece piece) {
            return units.stream().filter(u -> u.piece() == piece).toList();
        }
    }

    record Stock(int usable, int alreadyDone, int incompatible, Component noneProblem) {}

    record InvSlot(int slot, ItemStack stack) {}

    private record Book(int slot, int repairCost) {}

    private record Target(Holder<Enchantment> holder, int level) {}

    private AnvilPlanner() {}

    static List<InvSlot> fromMenu(AnvilMenu menu) {
        List<InvSlot> slots = new ArrayList<>();
        for (int slot = FIRST_INVENTORY_SLOT; slot < menu.slots.size(); slot++) {
            slots.add(new InvSlot(slot, menu.getSlot(slot).getItem()));
        }
        return slots;
    }

    static List<InvSlot> fromInventory(Inventory inventory) {
        List<InvSlot> slots = new ArrayList<>();
        List<ItemStack> items = inventory.getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) slots.add(new InvSlot(i, items.get(i)));
        return slots;
    }

    static Result plan(Player player, List<InvSlot> inventory, AnvilConfig config) {
        List<Component> problems = new ArrayList<>();
        List<Component> info = new ArrayList<>();
        boolean creative = player.hasInfiniteMaterials();
        Registry<Enchantment> registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        // Solo cuentan los libros de un encantamiento. Se gastan primero los de menos penalización.
        Map<String, List<Book>> bookLists = new HashMap<>();
        Map<Piece, List<InvSlot>> pieceSlots = new LinkedHashMap<>();
        for (InvSlot inv : inventory) {
            ItemStack stack = inv.stack();
            if (stack.is(Items.ENCHANTED_BOOK)) {
                ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                if (stored.size() != 1) continue;
                var entry = stored.entrySet().iterator().next();
                bookLists.computeIfAbsent(bookKey(entry.getKey(), entry.getIntValue()), k -> new ArrayList<>())
                        .add(new Book(inv.slot(), repairCost(stack)));
            } else {
                for (Piece piece : Piece.values()) {
                    if (stack.is(piece.item)) pieceSlots.computeIfAbsent(piece, k -> new ArrayList<>()).add(inv);
                }
            }
        }
        Map<String, Deque<Book>> books = new HashMap<>();
        bookLists.forEach((key, list) -> {
            list.sort(Comparator.comparingInt(Book::repairCost));
            books.put(key, new ArrayDeque<>(list));
        });

        List<Count> pieceCounts = new ArrayList<>();
        Map<String, Integer> booksNeeded = new LinkedHashMap<>();
        Map<String, Component> bookNames = new HashMap<>();
        Map<Integer, Integer> initialSlots = new HashMap<>();
        List<PiecePlan> ready = new ArrayList<>();
        List<PiecePlan> units = new ArrayList<>();
        Map<Piece, Stock> stock = new LinkedHashMap<>();
        int[] nextNode = {0};
        int withoutBooks = 0;

        for (Piece piece : Piece.values()) {
            AnvilConfig.PieceConfig pc = config.piece(piece);
            if (!pc.enabled || pc.enchantments.isEmpty()) continue;
            List<InvSlot> found = pieceSlots.getOrDefault(piece, List.of());
            boolean equipped = player.getItemBySlot(piece.equipSlot).is(piece.item);

            List<Target> targets = resolveTargets(piece, pc, registry, problems);
            if (targets == null) continue;

            // Primero las piezas a las que les faltan menos libros, luego las de menos penalización.
            record Candidate(InvSlot inv, List<Target> needed) {}
            List<Candidate> candidates = new ArrayList<>();
            int alreadyDone = 0;
            int incompatible = 0;
            for (InvSlot inv : found) {
                ItemEnchantments current = EnchantmentHelper.getEnchantmentsForCrafting(inv.stack());
                if (conflicts(current, targets)) {
                    incompatible++;
                    continue;
                }
                List<Target> needed = targets.stream().filter(t -> current.getLevel(t.holder()) < t.level()).toList();
                if (needed.isEmpty()) alreadyDone++;
                else candidates.add(new Candidate(inv, needed));
            }
            candidates.sort(Comparator.<Candidate>comparingInt(c -> c.needed().size())
                    .thenComparingInt(c -> repairCost(c.inv().stack())));

            if (alreadyDone > 0) info.add(Component.literal(piece.plural + ": " + alreadyDone + " ya completas, no cuentan."));
            if (incompatible > 0) info.add(Component.literal(piece.plural + ": " + incompatible + " incompatibles, no cuentan."));
            stock.put(piece, new Stock(candidates.size(), alreadyDone, incompatible, null));

            int count;
            if (Boolean.TRUE.equals(pc.useCount)) {
                count = pc.count;
                pieceCounts.add(new Count(Kind.PIECE, Component.literal(piece.plural), Math.min(candidates.size(), count), count));
                if (candidates.size() < count && equipped) {
                    info.add(Component.literal(piece.plural + ": la equipada no cuenta."));
                }
            } else {
                if (found.isEmpty()) {
                    Component none = Component.literal("No hay " + piece.plural.toLowerCase(Locale.ROOT)
                            + " de netherite en el inventario"
                            + (equipped ? " (la equipada no cuenta)." : "."));
                    problems.add(none);
                    stock.put(piece, new Stock(0, alreadyDone, incompatible, none));
                    continue;
                }
                count = candidates.size();
                if (count == 0) continue;
                pieceCounts.add(new Count(Kind.PIECE, Component.literal(piece.plural + " (todas)"), count, count));
            }

            boolean reportedTooExpensive = false;
            for (int i = 0; i < count; i++) {
                // Sin pieza se calcula como una nueva, para poder enseñar libros y XP.
                Candidate candidate = i < candidates.size() ? candidates.get(i) : null;
                List<Target> needed = candidate != null ? candidate.needed() : targets;
                int itemPen = candidate != null ? repairCost(candidate.inv().stack()) : 0;
                boolean complete = candidate != null;
                boolean missingBook = false;

                int n = needed.size();
                List<Book> chosen = new ArrayList<>();
                int[] bookPen = new int[n];
                int[] bookVal = new int[n];
                for (int b = 0; b < n; b++) {
                    Target t = needed.get(b);
                    String key = bookKey(t.holder(), t.level());
                    booksNeeded.merge(key, 1, Integer::sum);
                    bookNames.putIfAbsent(key, Enchantment.getFullname(t.holder(), t.level()));
                    Deque<Book> available = books.get(key);
                    Book book = available == null ? null : available.poll();
                    if (book == null) {
                        complete = false;
                        missingBook = true;
                    }
                    chosen.add(book);
                    bookPen[b] = book == null ? 0 : book.repairCost();
                    // Un libro en la casilla derecha cuesta la mitad (mínimo 1) por nivel.
                    bookVal[b] = Math.max(1, t.holder().value().getAnvilCost() / 2) * t.level();
                }

                Opt best = config.combineBooks ? bestOrder(itemPen, bookPen, bookVal, creative) : inOrder(itemPen, bookPen, bookVal);
                if (best == null) {
                    if (!reportedTooExpensive) {
                        problems.add(Component.literal(unitName(piece, i + 1, count)
                                + ": cualquier orden llega a 40 niveles en algún paso."));
                        reportedTooExpensive = true;
                    }
                    giveBack(books, needed, chosen);
                    continue;
                }

                Node itemNode = new Node(nextNode[0]++, Component.literal(piece.label), List.of());
                Node[] bookNodes = new Node[n];
                for (int b = 0; b < n; b++) {
                    Component name = bookNames.get(bookKey(needed.get(b).holder(), needed.get(b).level()));
                    bookNodes[b] = new Node(nextNode[0]++, name, List.of(name));
                }
                List<Step> steps = new ArrayList<>();
                emit(best, itemNode, bookNodes, steps, nextNode);
                PiecePlan unit = new PiecePlan(piece, i + 1, count, steps, best.total);
                units.add(unit);

                // Solo puede pasar en modo simple: bestOrder ya descarta los pasos de 40 o más.
                if (!creative) {
                    for (int s = 0; s < steps.size(); s++) {
                        Step step = steps.get(s);
                        if (step.cost() < TOO_EXPENSIVE) continue;
                        if (!reportedTooExpensive) {
                            problems.add(Component.literal(unitName(piece, i + 1, count) + ", paso " + (s + 1) + " (")
                                    .append(step.label()).append("): " + step.cost()
                                    + " niveles, máximo 39. Activa Combinar libros o cambia el orden."));
                            reportedTooExpensive = true;
                        }
                        complete = false;
                        break;
                    }
                }
                if (!complete) {
                    giveBack(books, needed, chosen);
                    if (candidate != null && missingBook) withoutBooks++;
                    continue;
                }

                initialSlots.put(itemNode.id(), candidate.inv().slot());
                for (int b = 0; b < n; b++) initialSlots.put(bookNodes[b].id(), chosen.get(b).slot());
                ready.add(unit);
            }
        }

        List<Count> counts = new ArrayList<>(pieceCounts);
        booksNeeded.forEach((key, need) -> counts.add(new Count(Kind.BOOK, bookNames.get(key),
                bookLists.getOrDefault(key, List.of()).size(), need)));

        // Faltar libros solo impide empezar si no alcanzan para ninguna unidad.
        List<Component> warnings = new ArrayList<>();
        boolean piecesOk = pieceCounts.stream().allMatch(Count::ok);
        if (problems.isEmpty() && piecesOk && withoutBooks > 0) {
            if (ready.isEmpty()) {
                problems.add(Component.literal("No hay libros para ninguna unidad."));
            } else {
                warnings.add(Component.literal("Faltan libros para " + withoutBooks + " de "
                        + (ready.size() + withoutBooks) + " unidades. Se encantan " + ready.size() + "."));
            }
        }

        boolean ok = problems.isEmpty() && piecesOk;
        if (!ok || ready.isEmpty()) {
            if (ok && info.isEmpty()) info.add(Component.literal("No hay piezas activas con encantamientos."));
            return new Result(null, counts, units, problems, warnings, info, creative, stock);
        }
        return new Result(new Plan(ready, initialSlots, withoutBooks), counts, units, problems, warnings, info, creative, stock);
    }

    /** Devuelve los libros de una unidad que no se hace, para que los usen las siguientes. */
    private static void giveBack(Map<String, Deque<Book>> books, List<Target> needed, List<Book> chosen) {
        for (int b = 0; b < chosen.size(); b++) {
            Book book = chosen.get(b);
            if (book != null) books.get(bookKey(needed.get(b).holder(), needed.get(b).level())).offerFirst(book);
        }
    }

    static String unitName(Piece piece, int index, int count) {
        return piece.fullName() + (count > 1 ? " " + index + "/" + count : "");
    }

    /** "XP por espada: 15 niveles (total 45, tienes 8)", solo como dato: cada paso se paga por separado. */
    static Component describeXp(List<PiecePlan> units, int level) {
        Map<Piece, int[]> ranges = new LinkedHashMap<>();
        for (PiecePlan unit : units) {
            int[] r = ranges.computeIfAbsent(unit.piece(), k -> new int[]{Integer.MAX_VALUE, 0});
            r[0] = Math.min(r[0], unit.totalCost());
            r[1] = Math.max(r[1], unit.totalCost());
        }
        StringBuilder text = new StringBuilder("XP por ");
        if (ranges.size() == 1) {
            var e = ranges.entrySet().iterator().next();
            text.append(e.getKey().label.toLowerCase(Locale.ROOT)).append(": ").append(range(e.getValue()));
        } else {
            text.append("unidad: ");
            boolean first = true;
            for (var e : ranges.entrySet()) {
                if (!first) text.append(", ");
                text.append(e.getKey().label.toLowerCase(Locale.ROOT)).append(' ').append(range(e.getValue()));
                first = false;
            }
        }
        text.append(" niveles");
        int total = units.stream().mapToInt(PiecePlan::totalCost).sum();
        String extra = units.size() > 1 ? "total " + total + ", " : "";
        return Component.literal(text + " (" + extra + "tienes " + level + ")").withStyle(ChatFormatting.GRAY);
    }

    private static String range(int[] r) {
        return r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "-" + r[1];
    }

    /** Una línea por cada grupo de unidades con los mismos pasos, p. ej. "1) + Filo V: 5  2) ... = 20 niveles". */
    static List<Component> describeSteps(List<PiecePlan> units, boolean creative) {
        if (units.isEmpty()) return List.of();
        Map<String, List<PiecePlan>> groups = new LinkedHashMap<>();
        for (PiecePlan unit : units) {
            StringBuilder signature = new StringBuilder();
            for (Step step : unit.steps()) signature.append(step.label().getString()).append(':').append(step.cost()).append('|');
            groups.computeIfAbsent(signature.toString(), k -> new ArrayList<>()).add(unit);
        }

        List<Component> lines = new ArrayList<>();
        for (List<PiecePlan> group : groups.values()) {
            PiecePlan first = group.getFirst();
            MutableComponent line = Component.empty();
            if (groups.size() > 1) {
                StringBuilder which = new StringBuilder(group.size() > 1 ? "Unidades " : "Unidad ");
                for (int i = 0; i < group.size(); i++) which.append(i > 0 ? ", " : "").append(group.get(i).index());
                line.append(Component.literal(which + ": ").withStyle(ChatFormatting.GRAY));
            }
            for (int s = 0; s < first.steps().size(); s++) {
                Step step = first.steps().get(s);
                boolean tooExpensive = !creative && step.cost() >= TOO_EXPENSIVE;
                if (s > 0) line.append("  ");
                line.append(Component.literal((s + 1) + ") ").withStyle(ChatFormatting.GRAY))
                        .append(step.label()).append(": ")
                        .append(Component.literal(String.valueOf(step.cost()))
                                .withStyle(tooExpensive ? ChatFormatting.RED : ChatFormatting.YELLOW));
                if (tooExpensive) line.append(Component.literal(" demasiado caro").withStyle(ChatFormatting.RED));
            }
            line.append(Component.literal(" = " + first.totalCost() + " niveles" + (group.size() > 1 ? " cada una" : ""))
                    .withStyle(ChatFormatting.GOLD));
            lines.add(line);
        }
        if (units.size() > 1) {
            int total = units.stream().mapToInt(PiecePlan::totalCost).sum();
            lines.add(Component.literal("Total " + units.size() + " unidades: " + total + " niveles")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static List<Target> resolveTargets(Piece piece, AnvilConfig.PieceConfig pc, Registry<Enchantment> registry,
                                               List<Component> problems) {
        List<Target> targets = new ArrayList<>();
        boolean ok = true;
        for (var e : pc.enchantments.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            Optional<Holder.Reference<Enchantment>> holder = id == null ? Optional.empty() : registry.get(id);
            if (holder.isEmpty()) {
                problems.add(Component.literal(piece.label + ": encantamiento desconocido: " + e.getKey()));
                ok = false;
                continue;
            }
            Enchantment ench = holder.get().value();
            if (!ench.canEnchant(piece.stack())) {
                problems.add(Component.literal(piece.label + ": ").append(ench.description()).append(" no se puede aplicar."));
                ok = false;
                continue;
            }
            if (e.getValue() > ench.getMaxLevel()) {
                problems.add(Component.literal(piece.label + ": ").append(ench.description())
                        .append(" llega como máximo a " + ench.getMaxLevel() + "."));
                ok = false;
                continue;
            }
            targets.add(new Target(holder.get(), e.getValue()));
        }
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                if (!Enchantment.areCompatible(targets.get(i).holder(), targets.get(j).holder())) {
                    problems.add(Component.literal(piece.label + ": ")
                            .append(targets.get(i).holder().value().description()).append(" y ")
                            .append(targets.get(j).holder().value().description()).append(" son incompatibles."));
                    ok = false;
                }
            }
        }
        return ok ? targets : null;
    }

    private static boolean conflicts(ItemEnchantments current, List<Target> targets) {
        for (Holder<Enchantment> existing : current.keySet()) {
            for (Target t : targets) {
                if (!existing.equals(t.holder()) && !Enchantment.areCompatible(existing, t.holder())) return true;
            }
        }
        return false;
    }

    static String bookKey(Holder<Enchantment> holder, int level) {
        return holder.unwrapKey().map(k -> k.identifier().toString()).orElse(holder.toString()) + "#" + level;
    }

    static int repairCost(ItemStack stack) {
        return stack.getOrDefault(DataComponents.REPAIR_COST, 0);
    }

    private static final int ITEM_LEAF = -2;
    private static final int INNER = -1;

    /** Árbol de combinaciones. pen es la penalización (REPAIR_COST) del resultado; leaf, un libro o ITEM_LEAF/INNER. */
    private static final class Opt {
        final int pen;
        final int total;
        final int stepCost;
        final int leaf;
        final boolean isItem;
        final Opt left, right;

        Opt(int pen, int total, int stepCost, int leaf, boolean isItem, Opt left, Opt right) {
            this.pen = pen;
            this.total = total;
            this.stepCost = stepCost;
            this.leaf = leaf;
            this.isItem = isItem;
            this.left = left;
            this.right = right;
        }
    }

    /** Programación dinámica sobre subconjuntos de libros. Null si todos los órdenes llegan a 40 en algún paso. */
    @SuppressWarnings("unchecked")
    private static Opt bestOrder(int itemPen, int[] bookPen, int[] bookVal, boolean creative) {
        int n = bookPen.length;
        int full = (1 << n) - 1;
        int[] valSum = new int[full + 1];
        List<Opt>[] bookTrees = new List[full + 1];
        for (int mask = 1; mask <= full; mask++) {
            valSum[mask] = valSum[mask & (mask - 1)] + bookVal[Integer.numberOfTrailingZeros(mask)];
            if (Integer.bitCount(mask) == 1) {
                int i = Integer.numberOfTrailingZeros(mask);
                bookTrees[mask] = List.of(new Opt(bookPen[i], 0, 0, i, false, null, null));
                continue;
            }
            List<Opt> options = new ArrayList<>();
            for (int a = (mask - 1) & mask; a > 0; a = (a - 1) & mask) {
                combine(options, bookTrees[a], bookTrees[mask ^ a], valSum[mask ^ a], false, creative);
            }
            bookTrees[mask] = pareto(options);
        }

        List<Opt>[] itemTrees = new List[full + 1];
        itemTrees[0] = List.of(new Opt(itemPen, 0, 0, ITEM_LEAF, true, null, null));
        for (int mask = 1; mask <= full; mask++) {
            List<Opt> options = new ArrayList<>();
            for (int b = mask; b > 0; b = (b - 1) & mask) {
                combine(options, itemTrees[mask ^ b], bookTrees[b], valSum[b], true, creative);
            }
            itemTrees[mask] = pareto(options);
        }
        return itemTrees[full].stream().min(Comparator.comparingInt(o -> o.total)).orElse(null);
    }

    private static void combine(List<Opt> out, List<Opt> lefts, List<Opt> rights, int rightValue, boolean isItem,
                                boolean creative) {
        for (Opt l : lefts) {
            for (Opt r : rights) {
                long step = (long) l.pen + r.pen + rightValue;
                if (!creative && step >= TOO_EXPENSIVE) continue;
                if (step > Integer.MAX_VALUE / 4) continue;
                long pen = 2L * Math.max(l.pen, r.pen) + 1; // AnvilMenu.calculateIncreasedRepairCost
                if (pen > Integer.MAX_VALUE / 4) continue;
                out.add(new Opt((int) pen, (int) (l.total + r.total + step), (int) step, INNER, isItem, l, r));
            }
        }
    }

    /** Se queda con las opciones no dominadas en (penalización, coste total). */
    private static List<Opt> pareto(List<Opt> options) {
        options.sort(Comparator.<Opt>comparingInt(o -> o.pen).thenComparingInt(o -> o.total));
        List<Opt> kept = new ArrayList<>();
        int bestTotal = Integer.MAX_VALUE;
        for (Opt o : options) {
            if (o.total < bestTotal) {
                kept.add(o);
                bestTotal = o.total;
            }
        }
        return kept;
    }

    /** Modo simple: pieza + libro 1, resultado + libro 2... en el orden de la config. */
    private static Opt inOrder(int itemPen, int[] bookPen, int[] bookVal) {
        Opt current = new Opt(itemPen, 0, 0, ITEM_LEAF, true, null, null);
        for (int b = 0; b < bookPen.length; b++) {
            Opt book = new Opt(bookPen[b], 0, 0, b, false, null, null);
            long step = (long) current.pen + book.pen + bookVal[b];
            long pen = Math.min(Integer.MAX_VALUE / 4, 2L * Math.max(current.pen, book.pen) + 1);
            long total = Math.min(Integer.MAX_VALUE / 4, current.total + step);
            current = new Opt((int) pen, (int) total, (int) Math.min(Integer.MAX_VALUE / 4, step), INNER, true,
                    current, book);
        }
        return current;
    }

    /** books: los libros originales que lleva un nodo de libros combinados, para la etiqueta. */
    private record Node(int id, Component label, List<Component> books) {}

    private static Node emit(Opt o, Node item, Node[] books, List<Step> out, int[] nextNode) {
        if (o.leaf == ITEM_LEAF) return item;
        if (o.leaf >= 0) return books[o.leaf];
        Node left = emit(o.left, item, books, out, nextNode);
        Node right = emit(o.right, item, books, out, nextNode);
        int result = nextNode[0]++;
        Component stepLabel = o.isItem
                ? Component.literal("+ ").append(right.label())
                : Component.empty().append(left.label()).append(" + ").append(right.label());
        out.add(new Step(left.id(), right.id(), result, o.stepCost, !o.isItem, stepLabel));
        if (o.isItem) return new Node(result, item.label(), List.of());

        List<Component> merged = new ArrayList<>(left.books());
        merged.addAll(right.books());
        MutableComponent label = Component.literal("[");
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) label.append(", ");
            label.append(merged.get(i));
        }
        return new Node(result, label.append("]"), merged);
    }
}
