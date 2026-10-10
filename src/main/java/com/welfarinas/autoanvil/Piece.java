package com.welfarinas.autoanvil;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Las cinco piezas de netherite que sabe encantar el mod. */
public enum Piece {
    HELMET("helmet", Items.NETHERITE_HELMET, EquipmentSlot.HEAD),
    CHESTPLATE("chestplate", Items.NETHERITE_CHESTPLATE, EquipmentSlot.CHEST),
    LEGGINGS("leggings", Items.NETHERITE_LEGGINGS, EquipmentSlot.LEGS),
    BOOTS("boots", Items.NETHERITE_BOOTS, EquipmentSlot.FEET),
    SWORD("sword", Items.NETHERITE_SWORD, EquipmentSlot.MAINHAND);

    public final String key;
    public final Item item;
    public final EquipmentSlot equipSlot;

    Piece(String key, Item item, EquipmentSlot equipSlot) {
        this.key = key;
        this.item = item;
        this.equipSlot = equipSlot;
    }

    public ItemStack stack() {
        return new ItemStack(item);
    }

    /** "Casco" */
    public Component label() {
        return Component.translatable("autoanvil.piece." + key);
    }

    /** "Cascos" */
    public Component plural() {
        return Component.translatable("autoanvil.piece." + key + ".plural");
    }

    /** "casco", dentro de una frase. */
    public Component lower() {
        return Component.translatable("autoanvil.piece." + key + ".lower");
    }

    /** "Casco de netherite" */
    public Component fullName() {
        return Component.translatable("autoanvil.piece." + key + ".full");
    }
}
