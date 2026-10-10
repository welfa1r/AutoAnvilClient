package com.welfarinas.autoanvil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnvilConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoAnvil");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autoanvil.json");

    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 36;
    public static final List<String> UNITS = List.of("ms", "s", "min", "h");
    private static final long[] UNIT_MS = {1, 1000, 60_000, 3_600_000};
    public static final long MAX_DELAY_MS = 24 * 3_600_000L;
    public static final int TICK_MS = 50;

    /** Retardo entre clics, en delayUnit. */
    public double delayAmount = 150;
    public String delayUnit = "ms";
    /** Formato antiguo, en ticks. Se pasa a ms al cargar. */
    private Integer clickDelayTicks;
    /** No: libro a libro en el orden de la lista. */
    public boolean combineBooks = true;
    public boolean waitForXp = false;
    /** Nombre antiguo de combineBooks, solo para migrar. */
    private Boolean saveXp;
    public Map<String, PieceConfig> pieces = new LinkedHashMap<>();

    public static final class PieceConfig {
        public boolean enabled = true;
        /** No: todas las del inventario. Null en JSON antiguos. */
        public Boolean useCount = false;
        public int count = 1;
        /** "minecraft:protection" -> nivel. */
        public Map<String, Integer> enchantments = new LinkedHashMap<>();

        PieceConfig copy() {
            PieceConfig c = new PieceConfig();
            c.enabled = enabled;
            c.useCount = useCount;
            c.count = count;
            c.enchantments = new LinkedHashMap<>(enchantments);
            return c;
        }
    }

    private static AnvilConfig instance;

    public static AnvilConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void set(AnvilConfig config) {
        instance = config;
        config.save();
    }

    public static long unitMs(String unit) {
        return UNIT_MS[Math.max(0, UNITS.indexOf(unit))];
    }

    /** Entre 0 y 24 h. NaN o negativo pasa a 0. */
    public static double clampAmount(double amount, String unit) {
        double max = (double) MAX_DELAY_MS / unitMs(unit);
        return amount >= 0 ? Math.min(amount, max) : 0;
    }

    public long delayMs() {
        return Math.round(delayAmount * unitMs(delayUnit));
    }

    /** Ticks entre clics, redondeando hacia arriba. El cliente no puede hacer más de un clic por tick. */
    public int clickTicks() {
        return (int) Math.max(1, (delayMs() + TICK_MS - 1) / TICK_MS);
    }

    public PieceConfig piece(Piece piece) {
        return pieces.computeIfAbsent(piece.key, k -> new PieceConfig());
    }

    public AnvilConfig copy() {
        AnvilConfig c = new AnvilConfig();
        c.delayAmount = delayAmount;
        c.delayUnit = delayUnit;
        c.combineBooks = combineBooks;
        c.waitForXp = waitForXp;
        pieces.forEach((k, v) -> c.pieces.put(k, v.copy()));
        return c;
    }

    private static AnvilConfig load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                AnvilConfig config = GSON.fromJson(reader, AnvilConfig.class);
                if (config != null) {
                    config.sanitize();
                    return config;
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.error("No se pudo leer {}, se usan valores por defecto", FILE, e);
            }
        }
        AnvilConfig config = defaults();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("No se pudo guardar {}", FILE, e);
        }
    }

    private void sanitize() {
        boolean migrated = false;
        if (pieces == null) pieces = new LinkedHashMap<>();
        if (saveXp != null) {
            combineBooks = saveXp;
            saveXp = null;
            migrated = true;
        }
        if (clickDelayTicks != null) {
            delayAmount = Math.max(0, clickDelayTicks) * TICK_MS;
            delayUnit = "ms";
            clickDelayTicks = null;
            migrated = true;
        }
        if (delayUnit == null || !UNITS.contains(delayUnit)) delayUnit = "ms";
        delayAmount = clampAmount(delayAmount, delayUnit);
        for (Piece piece : Piece.values()) {
            PieceConfig pc = piece(piece);
            if (pc.enchantments == null) pc.enchantments = new LinkedHashMap<>();
            pc.count = Math.clamp(pc.count, MIN_COUNT, MAX_COUNT);
            // Antes de "Usar cantidad" la cantidad siempre era exacta.
            if (pc.useCount == null) {
                pc.useCount = true;
                migrated = true;
            }
            pc.enchantments.values().removeIf(level -> level == null || level <= 0);
        }
        if (migrated) save();
    }

    private static AnvilConfig defaults() {
        AnvilConfig c = new AnvilConfig();
        put(c, Piece.HELMET, "protection", 4, "unbreaking", 3, "mending", 1, "respiration", 3, "aqua_affinity", 1);
        put(c, Piece.CHESTPLATE, "protection", 4, "unbreaking", 3, "mending", 1);
        put(c, Piece.LEGGINGS, "protection", 4, "unbreaking", 3, "mending", 1);
        put(c, Piece.BOOTS, "protection", 4, "unbreaking", 3, "mending", 1, "feather_falling", 4, "depth_strider", 3);
        put(c, Piece.SWORD, "sharpness", 5, "unbreaking", 3, "mending", 1, "looting", 3, "fire_aspect", 2,
                "sweeping_edge", 3, "knockback", 2);
        return c;
    }

    private static void put(AnvilConfig c, Piece piece, Object... idLevelPairs) {
        PieceConfig pc = c.piece(piece);
        for (int i = 0; i < idLevelPairs.length; i += 2) {
            pc.enchantments.put("minecraft:" + idLevelPairs[i], (Integer) idLevelPairs[i + 1]);
        }
    }
}
