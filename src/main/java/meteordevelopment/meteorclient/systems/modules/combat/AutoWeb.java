package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.*;

public class AutoWeb extends Module {

    private enum TriggerMode { HOLD, ALWAYS }
    private enum PlaceType   { WEB, LAVA }

    private static class CandidateFace {
        final BlockPos  supportPos;
        final Direction face;
        final PlaceType type;
        int             priority;
        LivingEntity    bestEnemy;

        CandidateFace(BlockPos supportPos, Direction face, PlaceType type, int priority, LivingEntity bestEnemy) {
            this.supportPos = supportPos;
            this.face       = face;
            this.type       = type;
            this.priority   = priority;
            this.bestEnemy  = bestEnemy;
        }

        BlockPos placePos() { return supportPos.offset(face); }
    }

    private static class EnemyInfo {
        final PlayerEntity entity;
        final boolean inCobweb;
        final boolean onFire;
        final boolean inAir;
        final BlockPos footPos;

        EnemyInfo(PlayerEntity e) {
            this.entity   = e;
            this.footPos  = e.getBlockPos();
            this.inCobweb = isInCobweb(e);
            this.onFire   = e.isOnFire();
            this.inAir    = !e.isOnGround();
        }

        private static boolean isInCobweb(PlayerEntity e) {
            return e.getEntityWorld().getBlockState(e.getBlockPos()).isOf(Blocks.COBWEB)
                || e.getEntityWorld().getBlockState(
                       BlockPos.ofFloored(e.getX(), e.getY() + 1, e.getZ())).isOf(Blocks.COBWEB);
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<TriggerMode> triggerMode = sgGeneral.add(new EnumSetting.Builder<TriggerMode>()
        .name("trigger-mode")
        .defaultValue(TriggerMode.HOLD)
        .build());

    private final Setting<Keybind> activateBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("activate")
        .defaultValue(Keybind.fromButton(3))
        .visible(() -> triggerMode.get() == TriggerMode.HOLD)
        .build());

    private final Setting<Double> selfRange = sgGeneral.add(new DoubleSetting.Builder().name("self-range").defaultValue(15).min(1).max(50).build());
    private final Setting<Double> enemyRange = sgGeneral.add(new DoubleSetting.Builder().name("enemy-range").defaultValue(5).min(1).max(20).build());
    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder().name("reach").defaultValue(4.5).min(1).max(6.0).sliderMax(6.0).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder().name("delay").defaultValue(3).min(0).max(20).build());
    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder().name("debug").defaultValue(true).build());

    private final Setting<SettingColor> webColor          = sgRender.add(new ColorSetting.Builder().name("web-color").defaultValue(new SettingColor(0, 200, 255, 40)).build());
    private final Setting<SettingColor> webLine           = sgRender.add(new ColorSetting.Builder().name("web-line").defaultValue(new SettingColor(0, 200, 255, 255)).build());
    private final Setting<SettingColor> lavaColor         = sgRender.add(new ColorSetting.Builder().name("lava-color").defaultValue(new SettingColor(255, 80, 0, 40)).build());
    private final Setting<SettingColor> lavaLine          = sgRender.add(new ColorSetting.Builder().name("lava-line").defaultValue(new SettingColor(255, 80, 0, 255)).build());
    private final Setting<SettingColor> waterSrcColor     = sgRender.add(new ColorSetting.Builder().name("water-src-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> waterSrcLine      = sgRender.add(new ColorSetting.Builder().name("water-src-line").defaultValue(new SettingColor(0, 100, 255, 255)).build());
    private final Setting<SettingColor> bucketAimColor    = sgRender.add(new ColorSetting.Builder().name("bucket-aim-color").defaultValue(new SettingColor(255, 255, 0, 60)).build());
    private final Setting<SettingColor> bucketAimLine     = sgRender.add(new ColorSetting.Builder().name("bucket-aim-line").defaultValue(new SettingColor(255, 255, 0, 255)).build());
    private final Setting<SettingColor> wBucketAimColor   = sgRender.add(new ColorSetting.Builder().name("water-bucket-aim-color").defaultValue(new SettingColor(0, 255, 255, 60)).build());
    private final Setting<SettingColor> wBucketAimLine    = sgRender.add(new ColorSetting.Builder().name("water-bucket-aim-line").defaultValue(new SettingColor(0, 255, 255, 255)).build());

    private final List<CandidateFace> allFaces     = new ArrayList<>();
    private BlockPos aimedFluid = null;
    private final List<BlockPos> isolatedWaterSrc = new ArrayList<>();

    private int     cd            = 0;
    private int     prevSlot      = -1;
    private int     swapBackTimer = 0;
    private boolean wasTriggered  = false;

    public AutoWeb() {
        super(Categories.Combat, "auto-web", "Multi-target cobweb / lava placement.");
    }

    @Override public void onActivate()   { if (debug.get()) info("模块已开启"); }
    @Override public void onDeactivate() {
        clearState();
        prevSlot = -1; swapBackTimer = 0; wasTriggered = false;
        if (debug.get()) info("模块已关闭");
    }

    private void clearState() {
        allFaces.clear();
        isolatedWaterSrc.clear();
        aimedFluid = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        if (swapBackTimer > 0) {
            swapBackTimer--;
            if (swapBackTimer == 0 && prevSlot != -1) {
                mc.player.getInventory().setSelectedSlot(prevSlot);
                prevSlot = -1;
            }
        }
        if (cd > 0) cd--;

        boolean triggered = triggerMode.get() == TriggerMode.ALWAYS
                         || activateBind.get().isPressed();

        clearState();

        List<EnemyInfo> enemies = new ArrayList<>();
        double range = selfRange.get();
        for (PlayerEntity entity : mc.world.getEntitiesByClass(
                PlayerEntity.class,
                mc.player.getBoundingBox().expand(range + 1),
                this::isValidTarget)) {
            enemies.add(new EnemyInfo(entity));
        }

        scanFluids(enemies, range);
        buildAllFaces(enemies);

        aimedFluid = getLookedFluidPos();

        if (wasTriggered && !triggered && prevSlot != -1) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1; swapBackTimer = 0;
        }
        wasTriggered = triggered;

        if (!triggered || cd > 0) return;
        executeAction(enemies);
    }

    private void buildAllFaces(List<EnemyInfo> enemies) {
        if (enemies.isEmpty()) return;

        boolean hasWeb  = hasInHotbar(Items.COBWEB);
        boolean hasLava = hasInHotbar(Items.LAVA_BUCKET);
        if (!hasWeb && !hasLava) return;

        double eRange = enemyRange.get();
        Set<BlockPos> checkedPositions = new HashSet<>();

        for (EnemyInfo enemy : enemies) {
            BlockPos ep = enemy.footPos;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 3; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos pos = ep.add(dx, dy, dz);

                        if (!checkedPositions.add(pos)) continue;
                        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > selfRange.get() * selfRange.get()) continue;

                        BlockState state = mc.world.getBlockState(pos);
                        if (!isSolidSupport(state)) continue;

                        for (Direction dir : Direction.values()) {
                            BlockPos neighborPos = pos.offset(dir);
                            BlockState neighborState = mc.world.getBlockState(neighborPos);

                            if (!neighborState.isAir() && !neighborState.isReplaceable()) continue;

                            boolean canWeb  = hasWeb  && canPlaceWeb(neighborPos, neighborState);
                            boolean canLava = hasLava && canPlaceLava(neighborPos, neighborState);

                            if (!canWeb && !canLava) continue;

                            int webPrio  = canWeb  ? 1000 : 0; // Web 给固定高优先级
                            int lavaPrio = canLava ? calcPriority(neighborPos, PlaceType.LAVA, enemies, eRange) : 0;

                            addBestFace(pos, dir, webPrio, lavaPrio, enemies);
                        }
                    }
                }
            }
        }
        allFaces.sort(Comparator.comparingInt((CandidateFace f) -> f.priority).reversed());
    }

    private void addBestFace(BlockPos supportPos, Direction face, int webPrio, int lavaPrio, List<EnemyInfo> enemies) {
        LivingEntity bestEnemy = findBestEnemyForPos(supportPos.offset(face), enemies, enemyRange.get());
        if (webPrio > 0 && lavaPrio > 0) {
            allFaces.add(new CandidateFace(supportPos, face, webPrio >= lavaPrio ? PlaceType.WEB : PlaceType.LAVA, Math.max(webPrio, lavaPrio), bestEnemy));
        } else if (webPrio > 0) {
            allFaces.add(new CandidateFace(supportPos, face, PlaceType.WEB, webPrio, bestEnemy));
        } else if (lavaPrio > 0) {
            allFaces.add(new CandidateFace(supportPos, face, PlaceType.LAVA, lavaPrio, bestEnemy));
        }
    }

    private int calcPriority(BlockPos placePos, PlaceType type, List<EnemyInfo> enemies, double eRange) {
        int bestPrio = 0;
        Vec3d placeCenter = Vec3d.ofCenter(placePos);
        boolean hasWeb  = hasInHotbar(Items.COBWEB);
        boolean hasLava = hasInHotbar(Items.LAVA_BUCKET);
        double eRangeSq = eRange * eRange;

        for (EnemyInfo enemy : enemies) {
            Vec3d ePos = new Vec3d(enemy.entity.getX(), enemy.entity.getY(), enemy.entity.getZ());
            if (placeCenter.squaredDistanceTo(ePos) > eRangeSq) continue;

            int prio = calcBasePriority(enemy, type, hasWeb && !hasLava, hasLava && !hasWeb);
            if (prio > bestPrio) bestPrio = prio;
        }
        return bestPrio;
    }

    private int calcBasePriority(EnemyInfo enemy, PlaceType type, boolean forceWeb, boolean forceLava) {
        if (enemy.inCobweb) return type == PlaceType.LAVA ? 100 : 10;
        if (enemy.onFire) return type == PlaceType.WEB ? 50 : 20;
        if (enemy.inAir) {
            if (forceLava) return type == PlaceType.LAVA ? 55 : 5;
            if (forceWeb)  return type == PlaceType.WEB  ? 60 : 5;
            return type == PlaceType.WEB ? 60 : 25;
        }
        if (forceLava) return type == PlaceType.LAVA ? 50 : 5;
        if (forceWeb)  return type == PlaceType.WEB  ? 45 : 5;
        return type == PlaceType.LAVA ? 50 : 30;
    }

    private LivingEntity findBestEnemyForPos(BlockPos pos, List<EnemyInfo> enemies, double eRange) {
        Vec3d center = Vec3d.ofCenter(pos);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        double eRangeSq = eRange * eRange;
        for (EnemyInfo e : enemies) {
            Vec3d ePos = new Vec3d(e.entity.getX(), e.entity.getY(), e.entity.getZ());
            double d = center.squaredDistanceTo(ePos);
            if (d <= eRangeSq && d < bestDist) {
                bestDist = d;
                best = e.entity;
            }
        }
        return best;
    }

    private void executeAction(List<EnemyInfo> enemies) {
        boolean hasLava        = hasInHotbar(Items.LAVA_BUCKET);
        boolean hasWeb         = hasInHotbar(Items.COBWEB);
        boolean hasBucket      = hasInHotbar(Items.BUCKET);
        boolean hasWaterBucket = hasInHotbar(Items.WATER_BUCKET);
        boolean holdingWeb     = mc.player.getMainHandStack().isOf(Items.COBWEB);

        // 检查是否有敌人在蜘蛛网上
        boolean hasEnemyInCobweb = enemies.stream().anyMatch(e -> e.inCobweb);

        if (aimedFluid != null) {
            FluidState fs = mc.world.getBlockState(aimedFluid).getFluidState();

            if (hasBucket && fs.isStill()) {
                scoopFluid(aimedFluid, "收液体");
                return;
            }
            if (hasWaterBucket && isWaterSource(aimedFluid)) {
                placeWater(aimedFluid);
                return;
            }
        }

        double reachVal = reach.get();
        Vec3d cam = mc.player.getCameraPosVec(1.0F);
        Vec3d end = cam.add(mc.player.getRotationVec(1.0F).multiply(reachVal));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cam, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos supportPos = hit.getBlockPos();
        Direction face = hit.getSide();
        BlockPos placePos = supportPos.offset(face);
        BlockState placeState = mc.world.getBlockState(placePos);

        Vec3d hitVec = Vec3d.ofCenter(supportPos).add(Vec3d.of(face.getVector()).multiply(0.5));
        if (mc.player.getEyePos().squaredDistanceTo(hitVec) > reachVal * reachVal) return;

        if (!placeState.isAir() && !placeState.isReplaceable()) return;
        if (isAnyWater(placeState)) return;

        boolean canWeb  = hasWeb  && canPlaceWeb(placePos, placeState);
        boolean canLava = hasLava && canPlaceLava(placePos, placeState);

        if (!canWeb && !canLava) return;

        // 如果有敌人在蜘蛛网上，优先放 web
        if (hasEnemyInCobweb && canWeb) {
            placeAt(supportPos, face, PlaceType.WEB);
        } else if (canWeb) {
            // 如果有 web，优先放 web（即使没有敌人在蜘蛛网上）
            placeAt(supportPos, face, PlaceType.WEB);
        } else if (canLava) {
            int lavaPrio = calcPriority(placePos, PlaceType.LAVA, enemies, enemyRange.get());
            if (lavaPrio > 0) {
                placeAt(supportPos, face, PlaceType.LAVA);
            } else {
                // 即使优先级为 0 也放岩浆（保持原来的逻辑）
                placeAt(supportPos, face, PlaceType.LAVA);
            }
        }
    }

    private void placeAt(BlockPos supportPos, Direction face, PlaceType type) {
        Item item = (type == PlaceType.WEB) ? Items.COBWEB : Items.LAVA_BUCKET;
        
        int slot = getSlot(item);
        if (slot == -1) return;

        Vec3d hitVec = Vec3d.ofCenter(supportPos).add(Vec3d.of(face.getVector()).multiply(0.5));
        if (mc.player.getEyePos().squaredDistanceTo(hitVec) > reach.get() * reach.get()) return;

        // 都需要切换和切回
        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Utils.rightClick();
        cd = delay.get();
        
        // 都需要切回
        swapBackTimer = 2;
    }

    private void scoopFluid(BlockPos targetPos, String name) {
        int slot = getSlot(Items.BUCKET);
        if (slot == -1) return;

        if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos)) > reach.get() * reach.get()) return;

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Utils.rightClick();
        cd = delay.get();
        swapBackTimer = 2;
    }

    private void placeWater(BlockPos targetPos) {
        int slot = getSlot(Items.WATER_BUCKET);
        if (slot == -1) return;

        if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos)) > reach.get() * reach.get()) return;

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Utils.rightClick();
        cd = delay.get();
        swapBackTimer = 2;
    }

    private boolean isSolidSupport(BlockState state) { return !state.isAir() && !state.isReplaceable(); }
    private boolean isAnyWater(BlockState state) { Fluid f = state.getFluidState().getFluid(); return f == Fluids.WATER || f == Fluids.FLOWING_WATER; }

    private boolean canPlaceWeb(BlockPos pos, BlockState state) {
        if (!state.isAir() && !state.isReplaceable()) return false;
        if (isAnyWater(state)) return false;
        Fluid f = state.getFluidState().getFluid();
        if (f == Fluids.LAVA || f == Fluids.FLOWING_LAVA) return false;
        return true;
    }

    private boolean canPlaceLava(BlockPos pos, BlockState state) {
        if (!state.isAir() && !state.isReplaceable()) return false;
        if (isAnyWater(state)) return false;
        FluidState fs = state.getFluidState();
        return !(fs.getFluid() == Fluids.LAVA && fs.isStill());
    }

    private boolean isValidTarget(PlayerEntity entity) {
        return entity != mc.player && entity.isAlive() && !entity.isCreative() && entity.squaredDistanceTo(mc.player) <= selfRange.get() * selfRange.get();
    }

    private boolean isWaterSource(BlockPos pos) {
        FluidState fs = mc.world.getBlockState(pos).getFluidState();
        return fs.getFluid() == Fluids.WATER && fs.isStill();
    }

    private void scanFluids(List<EnemyInfo> enemies, double range) {
        if (enemies.isEmpty()) return;
        Set<BlockPos> checked = new HashSet<>();
        for (EnemyInfo enemy : enemies) {
            BlockPos ep = enemy.footPos;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos pos = ep.add(dx, dy, dz);
                        if (!checked.add(pos) || mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > range * range) continue;

                        FluidState fs = mc.world.getBlockState(pos).getFluidState();
                        if (fs.getFluid() == Fluids.WATER && fs.isStill()) {
                            boolean isolated = true;
                            for (Direction d : Direction.values()) {
                                if (mc.world.getBlockState(pos.offset(d)).getFluidState().isStill()) { isolated = false; break; }
                            }
                            if (isolated) isolatedWaterSrc.add(pos);
                        }
                    }
                }
            }
        }
    }

    private BlockPos getLookedFluidPos() {
        double reachVal = reach.get();
        Vec3d cam = mc.player.getCameraPosVec(1.0F);
        Vec3d end = cam.add(mc.player.getRotationVec(1.0F).multiply(reachVal));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cam, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;

        BlockPos p = hit.getBlockPos();
        Fluid f = mc.world.getBlockState(p).getFluidState().getFluid();
        return (f == Fluids.WATER || f == Fluids.LAVA || f == Fluids.FLOWING_WATER || f == Fluids.FLOWING_LAVA) ? p : null;
    }

    private int getSlot(Item item) {
        if (mc.player.getMainHandStack().isOf(item)) return mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }
    private boolean hasInHotbar(Item item) { return getSlot(item) != -1; }

    @EventHandler
    private void onRender(Render3DEvent event) {
        boolean triggered = triggerMode.get() == TriggerMode.ALWAYS || activateBind.get().isPressed();

        for (BlockPos pos : isolatedWaterSrc) {
            event.renderer.box(pos, waterSrcColor.get(), waterSrcLine.get(), ShapeMode.Both, 0);
        }

        if (!triggered) return;

        if (debug.get()) {
            for (CandidateFace f : allFaces) {
                SettingColor side = (f.type == PlaceType.WEB) ? webColor.get() : lavaColor.get();
                SettingColor line = (f.type == PlaceType.WEB) ? webLine.get() : lavaLine.get();
                renderFace(event, f.supportPos, f.face, side, line);
            }
        }

        if (aimedFluid != null) {
            if (hasInHotbar(Items.BUCKET) && mc.world.getBlockState(aimedFluid).getFluidState().isStill()) {
                event.renderer.box(aimedFluid, bucketAimColor.get(), bucketAimLine.get(), ShapeMode.Both, 0);
            }
            if (hasInHotbar(Items.WATER_BUCKET) && isWaterSource(aimedFluid)) {
                event.renderer.box(aimedFluid, wBucketAimColor.get(), wBucketAimLine.get(), ShapeMode.Both, 0);
            }
        }
    }

    private void renderFace(Render3DEvent event, BlockPos pos, Direction face, SettingColor side, SettingColor line) {
        final double T = 0.02, I = 0.0;
        double bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        double x1=bx, y1=by, z1=bz, x2=bx+1, y2=by+1, z2=bz+1;
        switch (face) {
            case UP    -> { y1=by+1-T; y2=by+1+T; }
            case DOWN  -> { y1=by-T; y2=by+T; }
            case NORTH -> { z1=bz-T; z2=bz+T; }
            case SOUTH -> { z1=bz+1-T; z2=bz+1+T; }
            case WEST  -> { x1=bx-T; x2=bx+T; }
            case EAST  -> { x1=bx+1-T; x2=bx+1+T; }
            default    -> { return; }
        }
        event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, ShapeMode.Both, 0);
    }
}