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
import java.util.Map;

/** Configuración persistente en config/autoanvil.json. */
public final class AnvilConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoAnvil");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autoanvil.json");

    public static final int MIN_DELAY = 1;
    public static final int MAX_DELAY = 20;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 36;

    /** Ticks entre cada clic en el yunque (1 tick = 50 ms). */
    public int clickDelayTicks = 3;
    /**
     * Sí: orden más barato (puede juntar libros entre sí). No: libro a libro sobre la pieza,
     * en el orden de la lista de encantamientos.
     */
    public boolean combineBooks = true;
    /**
     * Sí: si no hay niveles para la siguiente unidad, espera a tenerlos y sigue sola.
     * No: se detiene y dice cuántas se encantaron y cuántos niveles faltan.
     */
    public boolean waitForXp = false;
    /** Nombre antiguo de {@link #combineBooks}; solo se lee para migrar (Gson no escribe los null). */
    private Boolean saveXp;
    public Map<String, PieceConfig> pieces = new LinkedHashMap<>();

    public static final class PieceConfig {
        /** Si se encanta esta pieza. No afecta a la cantidad. */
        public boolean enabled = true;
        /**
         * Sí: se encantan exactamente {@link #count} unidades. No: todas las de ese tipo que haya en el inventario.
         * Null solo en JSON antiguos (se migra al cargar).
         */
        public Boolean useCount = false;
        /** Cuántas unidades de esta pieza encantar en cada pasada (solo con useCount). */
        public int count = 1;
        /** id de encantamiento (p. ej. "minecraft:protection") -> nivel. */
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

    public PieceConfig piece(Piece piece) {
        return pieces.computeIfAbsent(piece.key, k -> new PieceConfig());
    }

    public AnvilConfig copy() {
        AnvilConfig c = new AnvilConfig();
        c.clickDelayTicks = clickDelayTicks;
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
        clickDelayTicks = Math.clamp(clickDelayTicks, MIN_DELAY, MAX_DELAY);
        for (Piece piece : Piece.values()) {
            PieceConfig pc = piece(piece);
            if (pc.enchantments == null) pc.enchantments = new LinkedHashMap<>();
            pc.count = Math.clamp(pc.count, MIN_COUNT, MAX_COUNT);
            // JSON de antes de "Usar cantidad": la cantidad siempre era exacta, así que se mantiene así.
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
