/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;

public class Sprint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> keepSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-sprint")
        .description("Whether to keep sprinting after attacking.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> unsprintOnHit = sgGeneral.add(new BoolSetting.Builder()
        .name("unsprint-on-hit")
        .description("Whether to stop sprinting before attacking, to ensure you get crits and sweep attacks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> unsprintInWater = sgGeneral.add(new BoolSetting.Builder()
        .name("unsprint-in-water")
        .description("Whether to stop sprinting when in water.")
        .defaultValue(true)
        .build()
    );

    public Sprint() {
        super(Categories.Movement, "sprint", "Automatically sprints.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTickMovement(TickEvent.Post event) {
        if (shouldSprint()) {
            mc.player.setSprinting(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPacketSend(PacketEvent.Send event) {
        if (!unsprintOnHit.get()) return;
        if (!(event.packet instanceof IPlayerInteractEntityC2SPacket packet)
            || packet.meteor$getType() != PlayerInteractEntityC2SPacket.InteractType.ATTACK) return;

        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        mc.player.setSprinting(false);
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (!unsprintOnHit.get() || !keepSprint.get()) return;
        if (!(event.packet instanceof IPlayerInteractEntityC2SPacket packet)
            || packet.meteor$getType() != PlayerInteractEntityC2SPacket.InteractType.ATTACK) return;

        if (shouldSprint() && !mc.player.isSprinting()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
            mc.player.setSprinting(true);
        }
    }

    public boolean shouldSprint() {
        // 1. GUI 打开时检查 GUIMove 设置
        if (mc.currentScreen != null && !Modules.get().get(GUIMove.class).sprint.get()) return false;

        // 2. 撞墙或潜行时不触发
        if (mc.player.horizontalCollision || mc.player.isSneaking()) return false;

        // 3. 在水里/水下时不触发（根据设置）

        // 4. 完全没有移动输入时不触发（站着不动）
        float forward = Math.abs(mc.player.forwardSpeed);
        float sideways = Math.abs(mc.player.sidewaysSpeed);
        if (forward <= 1.0E-5F && sideways <= 1.0E-5F) return false;

        // 5. 其他原版限制检查
        if (mc.player.hasBlindnessEffect()) return false;
        if (!mc.player.hasVehicle() && !mc.player.getHungerManager().canSprint()) return false;

        return true;
    }

    public boolean rageSprint() {
        return false;
    }

    public boolean unsprintInWater() {
        return isActive() && unsprintInWater.get();
    }

    public boolean stopSprinting() {
        return !isActive() || !keepSprint.get();
    }
}
