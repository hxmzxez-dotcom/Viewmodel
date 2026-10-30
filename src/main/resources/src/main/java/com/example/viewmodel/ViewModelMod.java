package com.example.viewmodel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * ViewModel - sunucu/anticheat test modu.
 * .viewmodel            -> ac/kapa
 * .viewmodel bind g     -> tus ata
 * Sadece kendi sunucunda test icin kullan.
 */
public class ViewModelMod implements ClientModInitializer {

    enum State { IDLE, GLIDE_START, ROCKET, APPROACH, STRIKE, ENTER, LAND }

    private static boolean enabled = false;
    private static int bindKey = InputUtil.GLFW_KEY_G;
    private static boolean keyWasDown = false;

    private State state = State.IDLE;
    private PlayerEntity target;
    private Vec3d origin;
    private int prevSlot = -1;
    private int timer = 0;
    private int rocketCooldown = 0;
    private double lastDist = 999;
    private Vec3d pushDir;
    private net.minecraft.util.math.BlockPos targetCell;
    private int cooldown = 0;
    private int hits = 0;

    private static final double REACH = 3.0;
    private static final int TIMEOUT = 40;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            if (!msg.startsWith(".viewmodel")) return true;
            String[] a = msg.trim().split("\\s+");
            if (a.length >= 3 && a[1].equalsIgnoreCase("bind")) {
                try {
                    bindKey = InputUtil.fromTranslationKey("key.keyboard." + a[2].toLowerCase()).getCode();
                    info("Tus atandi: " + a[2].toUpperCase());
                } catch (Exception e) {
                    info("Gecersiz tus: " + a[2]);
                }
            } else {
                toggle();
            }
            return false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
        info("§6[⚔ ViewModel] §f" + (enabled ? "ACIK" : "KAPALI"));
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        boolean down = InputUtil.isKeyPressed(mc.getWindow(), bindKey);
        if (down && !keyWasDown && mc.currentScreen == null) toggle();
        keyWasDown = down;

        if (!enabled) return;

        if (cooldown > 0) cooldown--;

        if (state == State.IDLE) {
            if (cooldown > 0) return;
            target = findTightTarget(mc, p);
            if (target == null) return;

            origin = p.getPos();
            prevSlot = p.getInventory().getSelectedSlot();
            timer = 0;
            lastDist = 999;

            if (canReach(p, target)) {
                targetCell = target.getBlockPos();
                pushDir = findPushDir(mc, targetCell, p);
                state = State.STRIKE;
            }
            else {
                if (headroom(mc, p.getBlockPos()) < 2) return;
                if (p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
                    info("Elytra takili degil."); prevSlot = -1; return;
                }
                if (findSlot(p, Items.FIREWORK_ROCKET) < 0) { info("Fisek yok."); prevSlot = -1; return; }
                state = State.GLIDE_START;
            }
        }

        if (target == null || !target.isAlive() || ++timer > TIMEOUT) { finish(p); return; }
        if (rocketCooldown > 0) rocketCooldown--;

        switch (state) {
            case GLIDE_START -> {
                mc.options.sneakKey.setPressed(true);
                if (p.isOnGround()) {
                    mc.options.jumpKey.setPressed(true);
                } else {
                    mc.options.jumpKey.setPressed(false);
                    mc.options.sneakKey.setPressed(false);
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    fireRocket(mc, p);
                    state = State.APPROACH;
                }
            }
            case ROCKET -> {
                fireRocket(mc, p);
                state = State.APPROACH;
            }
            case APPROACH -> {
                if (canReach(p, target)) { state = State.STRIKE; return; }
                double dist = p.getPos().distanceTo(target.getPos());
                if (p.horizontalCollision && timer > 5) { finish(p); return; }
                if (timer % 5 == 0) {
                    if (lastDist - dist < 0.8) { finish(p); return; }
                    lastDist = dist;
