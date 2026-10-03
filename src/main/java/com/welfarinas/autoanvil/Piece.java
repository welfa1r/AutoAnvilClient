package com.welfarinas.autoanvil;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Las cinco piezas de netherite que sabe encantar el mod. */
public enum Piece {
    HELMET("helmet", "Casco", "Cascos", Items.NETHERITE_HELMET, EquipmentSlot.HEAD),
    CHESTPLATE("chestplate", "Pechera", "Pecheras", Items.NETHERITE_CHESTPLATE, EquipmentSlot.CHEST),
    LEGGINGS("leggings", "Pantalones", "Pantalones", Items.NETHERITE_LEGGINGS, EquipmentSlot.LEGS),
    BOOTS("boots", "Botas", "Botas", Items.NETHERITE_BOOTS, EquipmentSlot.FEET),
    SWORD("sword", "Espada", "Espadas", Items.NETHERITE_SWORD, EquipmentSlot.MAINHAND);

    public final String key;
    public final String label;
    public final String plural;
    public final Item item;
    public final EquipmentSlot equipSlot;

    Piece(String key, String label, String plural, Item item, EquipmentSlot equipSlot) {
        this.key = key;
        this.label = label;
        this.plural = plural;
        this.item = item;
        this.equipSlot = equipSlot;
    }

    public ItemStack stack() {
        return new ItemStack(item);
    }

    public String fullName() {
        return label + " de netherite";
    }
}
