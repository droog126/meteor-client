package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.KeyBindingAccessor;
import meteordevelopment.meteorclient.mixin.MinecraftClientAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.UltimateSprint;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class TriggerBotV2 extends Module {
    public static Entity currentTarget = null;
    public static final List<AttackRecord> attackRecords = new ArrayList<>();
    private static final int MAX_RECORDS = 5;

    public enum TargetAcquisition {
        CROSSHAIR(" (Crosshair)"),
        RAYCAST(" (Raycast)");

        public final String displayName;

        TargetAcquisition(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class TargetResult {
        public final Entity target;
        public final TargetAcquisition acquisition;

        public TargetResult(Entity target, TargetAcquisition acquisition) {
            this.target = target;
            this.acquisition = acquisition;
        }
    }

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
            .name("hit-threshold").description("连击平砍冷却阈值").defaultValue(0.927).min(0.0).max(1.0)
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

    private final Setting<Double> attackRangeBonus = sgTactics.add(new DoubleSetting.Builder()
            .name("attack-range-bonus").description("额外攻击距离（叠加在原版上）").defaultValue(0.03).min(0.0).max(1.0)
            .sliderMax(1.0).build());

    // ==================== 状态记录 ====================
    private boolean isFirstAttack = true;
    private boolean hasDoneAirSwing = false;  // 标记是否已完成本次激活的空挥

    // 攻击前快照（用于记录真实冷却）
    private float snapshotCooldown = 0f;
    private double snapshotDistance = 0;
    private boolean snapshotIsCrit = false;
    private TargetAcquisition snapshotAcquisition = null;

    // 记录标记（防止与 AttackEntityEvent 重复记录）
    private Entity pendingRecordTarget = null;

    // 鼠标按下检测
    private boolean wasMouseDown = false;
    private boolean isMouseDown = false;

    public TriggerBotV2() {
        super(Categories.Combat, "trigger-bot-v2", "首刀可设最小冷却，全局1次战术空挥，重锤瞬间破甲。");
    }

    @Override
    public void onActivate() {
        isFirstAttack = true;
        isMouseDown = false;
        wasMouseDown = false;
        hasDoneAirSwing = false;  // 重置空挥标记
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive())
            return;

        // 只处理左键
        if (event.button() != 0)
            return;

        if (event.action == KeyAction.Press) {
            isMouseDown = true;
            if (!wasMouseDown) {
                isFirstAttack = true;
            }
        } else if (event.action == KeyAction.Release) {
            isMouseDown = false;
        }
        wasMouseDown = isMouseDown;
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
        // 鼠标没按下，不做任何事
        if (!isMouseDown) {
            currentTarget = null;
            return;
        }

        if (isHoldingNonCombatItem()) {
            currentTarget = null;
            return;
        }

        TargetResult targetResult = getTarget();
        Entity target = targetResult.target;
        currentTarget = target;

        // 准星没对准任何人
        if (target == null) {
            // 空挥：鼠标按下 + 周围没人（无冷却限制，可连续挥）
            if (smartAirSwing.get()) {
                if (getNearbyValidTargetsCount(airSwingRange.get()) == 0) {
                    doLegitClick();
                }
            }
            return;
        }

        // 【重锤：永远无视任何冷却直接砸】
        if (isMace() && mc.player.fallDistance >= 1.5f) {
            saveAttackSnapshot(target, targetResult.acquisition);
            doNormalAttack(target);
            return;
        }
        // 【首刀：按设定阈值出手，使用重置疾跑】
        if (isFirstAttack) {
            boolean isFalling = canCrit();
            double required;
            if (isFalling) {
                required = critThreshold.get();
            } else {
                required = firstHitThreshold.get();
            }

            if (getSyncedCooldownProgress() >= required) {
                if (canCrit()) {
                    saveAttackSnapshot(target, targetResult.acquisition);
                    doCritAttack(target);
                } else {
                    saveAttackSnapshot(target, targetResult.acquisition);
                    doNormalAttack(target);
                }
            }
            return;
        }

        if (shouldAttack()) {
            saveAttackSnapshot(target, targetResult.acquisition);
            doNormalAttack(target);
            return;
        }
    }

    // 保存攻击前快照（真实冷却值）
    private void saveAttackSnapshot(Entity target, TargetAcquisition acquisition) {
        snapshotCooldown = getSyncedCooldownProgress();
        snapshotDistance = Math.sqrt(
                Math.pow(mc.player.getX() - target.getX(), 2) +
                        Math.pow(mc.player.getY() - target.getY(), 2) +
                        Math.pow(mc.player.getZ() - target.getZ(), 2));
        snapshotIsCrit = canCrit();
        snapshotAcquisition = acquisition;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onAttackEntity(AttackEntityEvent event) {
        if (!isActive() || mc.player == null || mc.world == null)
            return;

        Entity target = event.entity;
        if (target == null)
            return;

        // 如果已经记录过了（通过 doCritAttack/doNormalAttack），防止重复记录
        if (pendingRecordTarget != null && pendingRecordTarget == target) {
            return;
        }

        // 使用攻击前快照的值（真实冷却）
        recordAttack(target, snapshotCooldown, snapshotDistance, UltimateSprint.skipSprintSetting, snapshotIsCrit,
                snapshotAcquisition);

        // 首刀标记
        if (isFirstAttack) {
            isFirstAttack = false;
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onPostTick(TickEvent.Post event) {
        // 清除待记录标记
        pendingRecordTarget = null;
    }

    // ========== 攻击执行 ==========
    /** 重置疾跑刀（首刀 & 暴击） */
    private void doCritAttack(Entity target) {
        // 立即记录攻击（不依赖 AttackEntityEvent）
        if (pendingRecordTarget == null) {
            recordAttack(target, snapshotCooldown, snapshotDistance, UltimateSprint.skipSprintSetting, snapshotIsCrit,
                    snapshotAcquisition);
            pendingRecordTarget = target;
        }
        // 首刀标记
        if (isFirstAttack) {
            isFirstAttack = false;
        }

        if (UltimateSprint.skipSprintSetting) {
            attack(target);
            return;
        } else {
            UltimateSprint.requestCritUnsprint(() -> attack(target, false));
        }

    }

    /** 普通刀（后续平砍 & 重锤） */
    private void doNormalAttack(Entity target) {
        // 立即记录攻击（不依赖 AttackEntityEvent）
        if (pendingRecordTarget == null) {
            recordAttack(target, snapshotCooldown, snapshotDistance, UltimateSprint.skipSprintSetting, snapshotIsCrit,
                    snapshotAcquisition);
            pendingRecordTarget = target;
        }
        // 首刀标记
        if (isFirstAttack) {
            isFirstAttack = false;
        }

        attack(target);
    }

    private void doLegitClick() {
        // KeyBindingAccessor accessor = (KeyBindingAccessor) mc.options.attackKey;
        // accessor.meteor$setTimesPressed(accessor.meteor$getTimesPressed() + 1);
        ((MinecraftClientAccessor) mc).meteor$leftClick();

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
        }
    }

    private TargetResult getTarget() {
        // 优先准星
        if (mc.crosshairTarget instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (isValid(entity))
                return new TargetResult(entity, TargetAcquisition.CROSSHAIR);
        }

        return null;
        // 原版射线检测（眼睛到目标）
        // Entity target = getTargetByRayCast();
        // return new TargetResult(target, target != null ? TargetAcquisition.RAYCAST : null);
    }

    /**
     * 原版逻辑：从眼睛射射线到目标 hitbox
     */
    private Entity getTargetByRayCast() {
        double baseReach = mc.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        double reach = baseReach + attackRangeBonus.get();

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d lookVec = mc.player.getRotationVec(1.0f);
        Vec3d endPos = eyePos.add(lookVec.multiply(reach));

        Box searchBox = mc.player.getBoundingBox()
                .expand(reach)
                .union(new Box(eyePos, endPos));

        Entity bestTarget = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity candidate : mc.world.getOtherEntities(mc.player, searchBox, this::isValid)) {
            // 射线检测：准心是否对着目标
            Box hitbox = candidate.getBoundingBox();
            Optional<Vec3d> hit = hitbox.raycast(eyePos, endPos);
            if (hit.isEmpty())
                continue;

            // 视线检测：眼部到目标之间无方块遮挡（原版逻辑）
            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                    eyePos,
                    new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ()),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player));
            if (blockHit.getType() != HitResult.Type.MISS)
                continue;

            double dist = eyePos.squaredDistanceTo(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = candidate;
            }
        }

        return bestTarget;
    }

    // ========== 冷却 & TPS 同步判定 ==========
    private boolean shouldAttack() {
        boolean isFalling = canCrit();
        double required;
        if (isFalling) {
            required = critThreshold.get();
        } else {
            required = hitThreshold.get();
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

    public static void recordAttack(Entity target, float cooldownUsed, double distance, boolean skipSprint,
            boolean isCrit, TargetAcquisition acquisition) {
        AttackRecord record = new AttackRecord();
        record.targetName = target.getName().getString();
        record.cooldownUsed = cooldownUsed;
        record.distance = distance;
        record.skipSprint = skipSprint;
        record.isCrit = isCrit;
        record.acquisition = acquisition;
        record.timestamp = System.currentTimeMillis();

        attackRecords.add(0, record);
        if (attackRecords.size() > MAX_RECORDS) {
            attackRecords.remove(attackRecords.size() - 1);
        }
    }

    public static class AttackRecord {
        public String targetName;
        public float cooldownUsed;
        public double distance;
        public boolean skipSprint;
        public boolean isCrit;
        public TargetAcquisition acquisition;
        public long timestamp;

        public AttackRecord() {
        }
    }
}