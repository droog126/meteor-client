package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.MinecraftClientAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.UltimateSprint;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

public class TriggerBotV2 extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTactics = settings.createGroup("战术 (Tactics)");

    // ==================== 常规设置 ====================
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities").description("攻击的实体类型").defaultValue(Set.of(EntityType.PLAYER)).build());

    private final Setting<Boolean> checkTeams = sgGeneral.add(new BoolSetting.Builder()
        .name("check-teams").description("不攻击队友").defaultValue(true).build());

    private final Setting<Double> hitThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("hit-threshold").description("连击平砍冷却阈值").defaultValue(0.95).min(0.0).max(1.0).build());

    private final Setting<Double> critThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("crit-threshold").description("连击暴击冷却阈值").defaultValue(0.85).min(0.0).max(1.0).build());

    // ==================== 距离 + 角度判定设置 ====================
    private final Setting<Double> maxReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-reach").description("最大攻击距离 (距离平方判定)").defaultValue(3.0).min(1.0).max(6.0).sliderMax(6.0).build());

    private final Setting<Double> maxAngle = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-angle").description("最大视角夹角 (度)。准星偏离目标中心超过此角度则不触发").defaultValue(30.0).min(5.0).max(55.0).sliderMax(60.0).build());

    // ==================== 战术设置 ====================
    private final Setting<Boolean> smartAirSwing = sgTactics.add(new BoolSetting.Builder()
        .name("smart-air-swing").description("周围没人时自动空挥 (每次开启仅触发1次)").defaultValue(true).build());

    private final Setting<Double> airSwingRange = sgTactics.add(new DoubleSetting.Builder()
        .name("air-swing-range").description("探测周围没人的范围").defaultValue(4.5).visible(smartAirSwing::get).build());

    // ==================== 状态记录 ====================
    private boolean isFirstAttack = true;
    private boolean hasAirSwung = false;

    public TriggerBotV2() {
        super(Categories.Combat, "trigger-bot-v2", "全局1次首刀0冷却，全局1次战术空挥，重锤瞬间破甲。");
    }

    @Override
    public void onActivate() {
        isFirstAttack = true;
        hasAirSwung = false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.player.isUsingItem()) return;

        Entity target = getCrosshairTarget();

        // ================= 准星没有对准任何人 =================
        if (target == null) {
            if (smartAirSwing.get() && !hasAirSwung && mc.player.getAttackCooldownProgress(0.0f) >= 1.0f) {
                if (getNearbyValidTargetsCount(airSwingRange.get()) == 0) {
                    ((MinecraftClientAccessor) mc).meteor$leftClick();
                    hasAirSwung = true;
                }
            }
            return;
        }

        // ================= 准星对准了目标 =================

        // 【重锤无视冷却直接砸】
        if (isMace() && mc.player.fallDistance >= 1.5f) {
            UltimateSprint.requestCritUnsprint(() -> ((MinecraftClientAccessor) mc).meteor$leftClick());
            isFirstAttack = false;
            return;
        }

        // 【首刀无视冷却直接砍】
        if (isFirstAttack) {
            UltimateSprint.requestCritUnsprint(() -> ((MinecraftClientAccessor) mc).meteor$leftClick());
            isFirstAttack = false;
            return;
        }

        // 【后续连击】严格依据设定的冷却阈值
        boolean isFalling = canCrit();
        float progress = mc.player.getAttackCooldownProgress(0.5f);
        double required = isFalling ? critThreshold.get() : hitThreshold.get();

        if (progress >= required) {
            UltimateSprint.requestCritUnsprint(() -> ((MinecraftClientAccessor) mc).meteor$leftClick());
        }
    }

    // ========== 距离 + 视角夹角 目标获取（通用游戏开发数学原理） ==========
  private Entity getCrosshairTarget() {
        if (mc.world == null || mc.player == null) return null;

        Vec3d eyePos = mc.player.getCameraPosVec(1.0f);
        Vec3d lookVec = mc.player.getRotationVec(1.0f).normalize();
        double reachSq = maxReach.get() * maxReach.get();
        double maxAngleDeg = maxAngle.get();

        Entity bestTarget = null;
        double bestAngle = Double.MAX_VALUE;

        Box searchBox = mc.player.getBoundingBox().expand(maxReach.get());
        for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox, this::isValid)) {
            Vec3d targetPos = entity.getBoundingBox().getCenter();
            double distSq = eyePos.squaredDistanceTo(targetPos);
            if (distSq > reachSq) continue;

            Vec3d toTarget = targetPos.subtract(eyePos).normalize();
            double dot = lookVec.dotProduct(toTarget);
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double angle = Math.toDegrees(Math.acos(dot));

            if (angle > maxAngleDeg) continue;

            if (angle < bestAngle) {
                bestAngle = angle;
                bestTarget = entity;
            }
        }

        return bestTarget;
    }
    // 获取附近合法目标数量（用于战术空挥）
    private int getNearbyValidTargetsCount(double range) {
        if (mc.world == null) return 0;
        Box box = mc.player.getBoundingBox().expand(range);
        return mc.world.getOtherEntities(mc.player, box, this::isValid).size();
    }

    // 实体合法性判定
    private boolean isValid(Entity e) {
        if (e == null || !e.isAlive() || e == mc.player) return false;
        if (e instanceof LivingEntity le && le.getHealth() <= 0) return false;
        if (!entities.get().contains(e.getType())) return false;
        if (e instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (!Friends.get().shouldAttack(p)) return false;
            if (checkTeams.get() && isTeammate(p)) return false;
        }
        return !(e instanceof AnimalEntity a) || !a.isBaby();
    }

    private boolean isTeammate(PlayerEntity p) {
        if (mc.player.isTeammate(p)) return true;
        AbstractTeam myTeam = mc.player.getScoreboardTeam();
        AbstractTeam targetTeam = p.getScoreboardTeam();
        return myTeam != null && targetTeam != null && myTeam.getColor() == targetTeam.getColor();
    }

    private boolean isMace() {
        return mc.player.getMainHandStack().isOf(Items.MACE);
    }
    private boolean canCrit() {
        return !mc.player.isOnGround()
            && mc.player.fallDistance > 0.0f
            && mc.player.getVelocity().y < 0 
            && !mc.player.isClimbing()
            && !mc.player.isSubmergedInWater()
            && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
            && mc.player.getVehicle() == null;
    }
}