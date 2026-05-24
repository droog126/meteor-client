package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.MinecraftClientAccessor;
import meteordevelopment.meteorclient.mixin.KeyBindingAccessor;
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
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;

import net.minecraft.util.Hand;

import java.util.Set;

public class TriggerBotV2 extends Module {
    public static Entity currentTarget = null;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTactics = settings.createGroup("战术 (Tactics)");

    // ==================== 常规设置 ====================
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("攻击的实体类型").defaultValue(Set.of(EntityType.PLAYER)).build());

    private final Setting<Boolean> checkTeams = sgGeneral.add(new BoolSetting.Builder()
            .name("check-teams").description("不攻击队友").defaultValue(true).build());

    private final Setting<Double> firstHitThreshold = sgGeneral.add(new DoubleSetting.Builder()
            .name("first-hit-threshold").description("首刀最小冷却阈值").defaultValue(0.843).min(0.0).max(1.0)
            .build());

    private final Setting<Double> hitThreshold = sgGeneral.add(new DoubleSetting.Builder()
            .name("hit-threshold").description("连击平砍冷却阈值1").defaultValue(0.927).min(0.0).max(1.0)
            .build());

    private final Setting<Double> hitThreshold2 = sgGeneral.add(new DoubleSetting.Builder()
            .name("hit-threshold-2").description("连击平砍冷却阈值2（50%概率选择）").defaultValue(0.843).min(0.0).max(1.0)
            .build());

    private final Setting<Double> critThreshold = sgGeneral.add(new DoubleSetting.Builder()
            .name("crit-threshold").description("连击暴击冷却阈值").defaultValue(0.941).min(0.0).max(1.0)
            .build());

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
            .name("swing-hand").description("攻击时挥动手臂动画").defaultValue(true).build());

    // ==================== 战术设置 ====================
    private final Setting<Boolean> smartAirSwing = sgTactics.add(new BoolSetting.Builder()
            .name("smart-air-swing").description("周围没人时自动空挥（每次开启仅触发1次）").defaultValue(true).build());

    private final Setting<Double> airSwingRange = sgTactics.add(new DoubleSetting.Builder()
            .name("air-swing-range").description("探测周围没人的范围").defaultValue(4.5).visible(smartAirSwing::get).build());

    // ==================== 状态记录 ====================
    private boolean isFirstAttack = true;
    private boolean hasAirSwung = false;

    public TriggerBotV2() {
        super(Categories.Combat, "trigger-bot-v2", "首刀可设最小冷却，全局1次战术空挥，重锤瞬间破甲。");
    }

    @Override
    public void onActivate() {
        isFirstAttack = true;
        hasAirSwung = false;
    }

    @Override
    public void onDeactivate() {
        currentTarget = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        if (!isActive() || mc.player == null || mc.world == null || mc.player.isUsingItem()) {
            currentTarget = null;
            return;
        }

        if (isHoldingNonCombatItem()) {
            currentTarget = null;
            return;
        }

        Entity target = getTarget();
        currentTarget = target;

        // ================= 准星没有对准任何人 =================
        if (target == null) {
            if (smartAirSwing.get() && !hasAirSwung && isCooldownReady()) {
                if (getNearbyValidTargetsCount(airSwingRange.get()) == 0) {
                    doLegitClick();
                    hasAirSwung = true;
                }
            }
            return;
        }

        // 【重锤：永远无视任何冷却直接砸】
        if (isMace() && mc.player.fallDistance >= 1.5f) {
            doNormalAttack(target);
            isFirstAttack = false;
            return;
        }
        double critThreshold2 = UltimateSprint.skipSprintSetting ? critThreshold.get() : 0.77f;
        // 【首刀：按设定阈值出手，使用重置疾跑】
        if (isFirstAttack) {
            if (canCrit()) {
                if (getSyncedCooldownProgress() >= critThreshold2) {
                    doCritAttack(target);
                }
            } else {
                if (getSyncedCooldownProgress() >= hitThreshold.get()) {
                    doNormalAttack(target);
                }
            }
            return;
        }

        if (shouldAttack()) {
            doNormalAttack(target);
            return;
        }
    }

    // ========== 攻击执行 ==========
    /** 重置疾跑刀（首刀 & 暴击） */
    private void doCritAttack(Entity target) {
        if (UltimateSprint.skipSprintSetting) {
            attack(target);
            return;
        } else {
            UltimateSprint.requestCritUnsprint(() -> attack(target, false));
        }

    }

    /** 普通刀（后续平砍 & 重锤） */
    private void doNormalAttack(Entity target) {
        attack(target);
    }

    private void doLegitClick() {
        KeyBindingAccessor accessor = (KeyBindingAccessor) mc.options.attackKey;
        accessor.meteor$setTimesPressed(accessor.meteor$getTimesPressed() + 1);
    }

    private void attack(Entity target) {
        attack(target, false);
    }

    private void attack(Entity target, boolean isLegit) {

        if (mc.options.forwardKey.isPressed() && !UltimateSprint.skipSprintSetting) {
            UltimateSprint.setSkipSprintSetting();
        }

        if (isLegit) {
            doLegitClick();
        } else {
            doLegitClick();
            // mc.interactionManager.attackEntity(mc.player, target);
            // if (swingHand.get())
            //     mc.player.swingHand(Hand.MAIN_HAND);
        }

        // 检查是否处于疾跑且W键按下，如果是则设置跳过疾跑设置状态

    }

    private Entity getTarget() {
        if (mc.crosshairTarget instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (isValid(entity))
                return entity;
        }
        return null;
    }

    // ========== 冷却 & TPS 同步判定 ==========
    private boolean shouldAttack() {
        boolean isFalling = canCrit();
        double critThreshold2 = UltimateSprint.skipSprintSetting ? critThreshold.get() : 0.77f;
        double required;
        if (isFalling) {
            required = critThreshold2;
        } else {
            required = Math.random() < 0.5 ? hitThreshold.get() : hitThreshold2.get();
        }
        float progress = getSyncedCooldownProgress();
        return progress >= required;
    }

    private boolean isCooldownReady() {
        return getSyncedCooldownProgress() >= 1.0f;
    }

    private float getSyncedCooldownProgress() {
        return mc.player.getAttackCooldownProgress(0.5f);
    }

    private int getNearbyValidTargetsCount(double range) {
        if (mc.world == null)
            return 0;
        Box box = mc.player.getBoundingBox().expand(range);
        return mc.world.getOtherEntities(mc.player, box, this::isValid).size();
    }

    public boolean isValid(Entity e) {
        if (e == null || !e.isAlive() || e == mc.player)
            return false;
        if (e instanceof LivingEntity le && le.getHealth() <= 0)
            return false;
        if (!entities.get().contains(e.getType()))
            return false;
        if (e instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator())
                return false;
            if (!Friends.get().shouldAttack(p))
                return false;
            if (checkTeams.get() && isTeammate(p))
                return false;
        }
        return !(e instanceof AnimalEntity a) || !a.isBaby();
    }

    private boolean isTeammate(PlayerEntity p) {
        if (mc.player.isTeammate(p))
            return true;
        AbstractTeam myTeam = mc.player.getScoreboardTeam();
        AbstractTeam targetTeam = p.getScoreboardTeam();
        return myTeam != null && targetTeam != null && myTeam.getColor() == targetTeam.getColor();
    }

    private boolean isMace() {
        return mc.player.getMainHandStack().isOf(Items.MACE);
    }

    private boolean isHoldingNonCombatItem() {
        ItemStack stack = mc.player.getMainHandStack();
        return stack.isOf(Items.COBWEB)
                || stack.isOf(Items.LAVA_BUCKET)
                || stack.isOf(Items.WATER_BUCKET)
                || stack.isOf(Items.BUCKET);
    }

    private boolean canCrit() {
        return !mc.player.isOnGround()
                && mc.player.fallDistance > 0f
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
                && mc.player.getVehicle() == null;
    }

}