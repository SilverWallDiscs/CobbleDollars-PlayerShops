package dev.silver.cobbledollarsplayershops;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;

public final class ItemCodec {
    private ItemCodec() {}

    public static String encode(ItemStack stack, RegistryWrapper.WrapperLookup registries) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.encode(registries).toString();
    }

    public static ItemStack decode(String snbt, RegistryWrapper.WrapperLookup registries) {
        if (snbt == null || snbt.isBlank()) return ItemStack.EMPTY;
        try {
            NbtCompound nbt = StringNbtReader.parse(snbt);
            return ItemStack.fromNbt(registries, nbt).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            CobbleDollarsPlayerShops.LOGGER.error("Could not decode stored ItemStack: {}", snbt, e);
            return ItemStack.EMPTY;
        }
    }

    public static ItemStack template(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    public static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }
}
