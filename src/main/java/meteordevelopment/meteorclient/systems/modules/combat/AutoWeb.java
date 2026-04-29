package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.*;

public class AutoWeb extends Module {

    private enum TriggerMode { HOLD, ALWAYS }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<TriggerMode> triggerMode = sgGeneral.add(new EnumSetting.Builder<TriggerMode>()
        .name("trigger-mode")
        .description("HOLD = 按住激活键才工作；ALWAYS = 一直自动运行")
        .defaultValue(TriggerMode.HOLD)
        .build());

    private final Setting<Keybind> activateBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("activate")
        .description("仅在 HOLD 模式下生效")
        .defaultValue(Keybind.fromButton(3))
        .visible(() -> triggerMode.get() == TriggerMode.HOLD)
        .build());

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("目标放置/收集的作用距离")
        .defaultValue(7).min(1).max(12)
        .build());

    private final Setting<Double> fluidRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("fluid-range")
        .description("附近液体方块的渲染距离（以玩家为中心）")
        .defaultValue(5).min(1).max(12)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .defaultValue(3).min(0).max(20)
        .build());

    private final Setting<Integer> predictTicks = sgGeneral.add(new IntSetting.Builder()
        .name("predict-ticks")
        .description("对目标移动进行预测的tick数")
        .defaultValue(2).min(0).max(10)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("在聊天框输出详细的判断和放置日志，用于排查放不出的原因")
        .defaultValue(true)
        .build());

    // Render colors
    private final Setting<SettingColor> webColor       = sgRender.add(new ColorSetting.Builder().name("web-color").defaultValue(new SettingColor(0, 200, 255, 40)).build());
    private final Setting<SettingColor> webLine        = sgRender.add(new ColorSetting.Builder().name("web-line").defaultValue(new SettingColor(0, 200, 255, 255)).build());
    private final Setting<SettingColor> lavaColor      = sgRender.add(new ColorSetting.Builder().name("lava-color").defaultValue(new SettingColor(255, 80, 0, 40)).build());
    private final Setting<SettingColor> lavaLine       = sgRender.add(new ColorSetting.Builder().name("lava-line").defaultValue(new SettingColor(255, 80, 0, 255)).build());
    private final Setting<SettingColor> aimColor       = sgRender.add(new ColorSetting.Builder().name("aim-color").defaultValue(new SettingColor(0, 255, 0, 60)).build());
    private final Setting<SettingColor> aimLine        = sgRender.add(new ColorSetting.Builder().name("aim-line").defaultValue(new SettingColor(0, 255, 0, 255)).build());
    private final Setting<SettingColor> waterColor     = sgRender.add(new ColorSetting.Builder().name("water-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> waterLine      = sgRender.add(new ColorSetting.Builder().name("water-line").defaultValue(new SettingColor(0, 100, 255, 255)).build());
    private final Setting<SettingColor> fluidLavaColor = sgRender.add(new ColorSetting.Builder().name("fluid-lava-color").defaultValue(new SettingColor(255, 60, 0, 40)).build());
    private final Setting<SettingColor> fluidLavaLine  = sgRender.add(new ColorSetting.Builder().name("fluid-lava-line").defaultValue(new SettingColor(255, 60, 0, 255)).build());

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<BlockPos> webRenderPos  = new ArrayList<>();
    private final Map<BlockPos, List<Direction>> webRenderFaces  = new HashMap<>();
    private final List<BlockPos> lavaRenderPos = new ArrayList<>();
    private final Map<BlockPos, List<Direction>> lavaRenderFaces = new HashMap<>();
    private final List<BlockPos> fluidRenderPos = new ArrayList<>();

    // FIX 1: Split aimed tracking so web and lava can independently be "aimed"
    private BlockPos aimedWebPos  = null;
    private BlockPos aimedLavaPos = null;
    private BlockPos aimedFluid   = null;

    // Keep a unified aimedPos/aimedIsWeb for action logic (derived from the above)
    private BlockPos aimedPos   = null;
    private boolean  aimedIsWeb = false;

    private int cd = 0;
    private LivingEntity lockedTarget = null;

    private int     prevSlot      = -1;
    private int     swapBackTimer = 0;
    private boolean wasTriggered  = false;

    private enum ActionType { NONE, PLACE_WEB, PLACE_LAVA, SCOOP_WATER, SCOOP_LAVA, PLACE_WATER_FOR_BUCKET }
    private enum WaterPlaceState { NONE, PLACED }

    private WaterPlaceState waterPlaceState = WaterPlaceState.NONE;
    private BlockPos pendingScoopPos = null;

    public AutoWeb() {
        super(Categories.Combat, "auto-web", "Legal cobweb / lava placement.");
    }

    @Override
    public void onActivate() {
        if (debug.get()) info("模块已开启。等待目标锁定...");
    }

    @Override
    public void onDeactivate() {
        lockedTarget = null;
        clearRender();
        prevSlot = -1;
        swapBackTimer = 0;
        wasTriggered = false;
        waterPlaceState = WaterPlaceState.NONE;
        pendingScoopPos = null;
        if (debug.get()) info("模块已关闭。清空所有状态。");
    }

    private void clearRender() {
        webRenderPos.clear();
        webRenderFaces.clear();
        lavaRenderPos.clear();
        lavaRenderFaces.clear();
        fluidRenderPos.clear();
        // FIX 1: Clear both independent aimed positions
        aimedWebPos  = null;
        aimedLavaPos = null;
        aimedPos     = null;
        aimedIsWeb   = false;
        aimedFluid   = null;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

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

        boolean holdMode  = triggerMode.get() == TriggerMode.HOLD;
        boolean triggered = !holdMode || activateBind.get().isPressed();

        clearRender();
        updateTarget();
        scanNearbyFluids();

        if (lockedTarget != null && lockedTarget.isAlive()
                && lockedTarget.distanceTo(mc.player) <= targetRange.get()) {
            buildPlaceable(lockedTarget);
        } else {
            lockedTarget = null;
        }

        // FIX 1: Track aimed pos independently for web and lava
        aimedFluid   = getLookedFluidPos();
        aimedWebPos  = getLookedPlacePos(webRenderPos);
        aimedLavaPos = getLookedPlacePos(lavaRenderPos);

        // For action logic: prefer web over lava if both aimed (web takes priority)
        if (aimedWebPos != null) {
            aimedPos   = aimedWebPos;
            aimedIsWeb = true;
        } else if (aimedLavaPos != null) {
            aimedPos   = aimedLavaPos;
            aimedIsWeb = false;
        } else {
            aimedPos   = null;
            aimedIsWeb = false;
        }

        if (wasTriggered && !triggered) {
            if (prevSlot != -1) {
                mc.player.getInventory().setSelectedSlot(prevSlot);
                prevSlot = -1;
                swapBackTimer = 0;
            }
        }
        wasTriggered = triggered;

        if (!triggered) return;
        if (cd > 0) return;

        Item mainHand = mc.player.getMainHandStack().getItem();

        boolean canWeb         = hasInHotbar(Items.COBWEB);
        boolean canLava        = hasInHotbar(Items.LAVA_BUCKET);
        boolean canBucket      = hasInHotbar(Items.BUCKET);
        boolean canWaterBucket = hasInHotbar(Items.WATER_BUCKET);

        boolean isWaterAimed = aimedFluid != null && isWaterSource(aimedFluid);
        boolean isLavaAimed  = aimedFluid != null && isLavaSource(aimedFluid);

        // Check if the aimed placement position has a cobweb support
        boolean aimOnCobweb = false;
        if (aimedPos != null) {
            Map<BlockPos, List<Direction>> faceMap = aimedIsWeb ? webRenderFaces : lavaRenderFaces;
            Direction face = getBestFace(aimedPos, faceMap);
            if (face != null) {
                BlockPos support = aimedPos.offset(face.getOpposite());
                aimOnCobweb = mc.world.getBlockState(support).isOf(Blocks.COBWEB);
            }
        }

        ActionType action = ActionType.NONE;

        if (waterPlaceState == WaterPlaceState.PLACED && pendingScoopPos != null) {
            if (isWaterSource(pendingScoopPos) && canBucket) {
                aimedFluid = pendingScoopPos;
                action = ActionType.SCOOP_WATER;
            } else {
                waterPlaceState = WaterPlaceState.NONE;
                pendingScoopPos = null;
            }
        }

        if (action == ActionType.NONE && isWaterAimed && canBucket) {
            action = ActionType.SCOOP_WATER;
        } else if (action == ActionType.NONE && isWaterAimed && !canBucket && canWaterBucket) {
            action = ActionType.PLACE_WATER_FOR_BUCKET;
        } else if (action == ActionType.NONE && aimOnCobweb && canLava && aimedPos != null) {
            action = ActionType.PLACE_LAVA;
        } else if (action == ActionType.NONE && isLavaAimed && canBucket) {
            action = ActionType.SCOOP_LAVA;
        } else if (action == ActionType.NONE && aimedPos != null) {
            boolean inWebList  = webRenderPos.contains(aimedPos);
            boolean inLavaList = lavaRenderPos.contains(aimedPos);

            if (mainHand == Items.COBWEB && canWeb && inWebList) {
                action = ActionType.PLACE_WEB;
            } else if (mainHand == Items.LAVA_BUCKET && canLava && inLavaList) {
                action = ActionType.PLACE_LAVA;
            } else if (aimOnCobweb && canLava && inLavaList) {
                action = ActionType.PLACE_LAVA;
            } else if (aimOnCobweb && canWeb && inWebList) {
                action = ActionType.PLACE_WEB;
            } else if (inWebList && canWeb) {
                action = ActionType.PLACE_WEB;
            } else if (inLavaList && canLava) {
                action = ActionType.PLACE_LAVA;
            }
        }

        switch (action) {
            case PLACE_WEB   -> placeBlock(aimedPos, Items.COBWEB,      "蜘蛛网", webRenderFaces);
            case PLACE_LAVA  -> placeBlock(aimedPos, Items.LAVA_BUCKET, "岩浆",   lavaRenderFaces);
            case SCOOP_WATER -> {
                scoopFluid(aimedFluid, "收水");
                if (waterPlaceState == WaterPlaceState.PLACED && pendingScoopPos != null
                        && aimedFluid != null && aimedFluid.equals(pendingScoopPos)) {
                    waterPlaceState = WaterPlaceState.NONE;
                    pendingScoopPos = null;
                }
            }
            case SCOOP_LAVA             -> scoopFluid(aimedFluid, "收岩浆");
            case PLACE_WATER_FOR_BUCKET -> placeWaterForBucket(aimedFluid);
            default -> {}
        }
    }

    // ── Placement helpers ─────────────────────────────────────────────────────

    private void placeBlock(BlockPos targetPos, Item item, String name, Map<BlockPos, List<Direction>> faceMap) {
        if (targetPos == null) return;
        int slot = getSlot(item);
        if (slot == -1) {
            if (debug.get()) warning("放置" + name + "失败：背包没有该物品");
            return;
        }

        List<Direction> faces = faceMap.get(targetPos);
        if (faces == null || faces.isEmpty()) {
            if (debug.get()) warning("放置" + name + "失败：" + targetPos.toShortString() + " 没有有效支撑面");
            return;
        }

        if (debug.get()) info("准备放" + name + " @ " + targetPos.toShortString() + "，可用面: " + faces.size());

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        for (Direction face : faces) {
            BlockPos support = targetPos.offset(face.getOpposite());
            Vec3d hitVec = Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(0.5));
            BlockHitResult bhr = new BlockHitResult(hitVec, face, support, false);

            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            if (!result.isAccepted()) {
                result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            }

            if (result.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                if (debug.get()) info(">> " + name + " 放置成功（面: " + face + "）！进入冷却...");
                cd = delay.get();
                swapBackTimer = 2;
                return;
            }
        }

        if (debug.get()) warning(">> " + name + " 所有面尝试均失败！");
        swapBackTimer = 1;
    }

    private void scoopFluid(BlockPos targetPos, String name) {
        if (targetPos == null) return;
        int slot = getSlot(Items.BUCKET);
        if (slot == -1) return;

        if (debug.get()) info("准备" + name + " @ " + targetPos.toShortString());

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Vec3d hitVec = Vec3d.ofCenter(targetPos);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, targetPos, false);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        if (!result.isAccepted()) {
            result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }

        if (result.isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (debug.get()) info(">> " + name + " 成功！进入冷却...");
            cd = delay.get();
            swapBackTimer = 2;
        } else {
            if (debug.get()) warning(">> " + name + " 失败！");
            swapBackTimer = 1;
        }
    }

    private void placeWaterForBucket(BlockPos targetPos) {
        if (targetPos == null) return;
        int slot = getSlot(Items.WATER_BUCKET);
        if (slot == -1) {
            if (debug.get()) warning("无法获取空桶：背包没有水桶");
            return;
        }

        if (debug.get()) info("准备对水源方块放水重合 @ " + targetPos.toShortString());

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Vec3d hitVec = Vec3d.ofCenter(targetPos);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, targetPos, false);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        if (!result.isAccepted()) {
            result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }

        if (result.isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (debug.get()) info(">> 放水重合成功，下一 tick 检查水是否已消失并收回");
            waterPlaceState = WaterPlaceState.PLACED;
            pendingScoopPos = targetPos;
            cd = delay.get();
            swapBackTimer = 2;
        } else {
            if (debug.get()) warning(">> 放水重合失败");
            swapBackTimer = 1;
        }
    }

    // ── Validity checks ───────────────────────────────────────────────────────

    private boolean isValidForWeb(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean isValidForLava(BlockPos pos, boolean noFluidRestriction) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isReplaceable()) return false;
        if (noFluidRestriction) return true;
        Fluid fluid = state.getFluidState().getFluid();
        return fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER;
    }

    private boolean isWaterSource(BlockPos pos) {
        return mc.world.getBlockState(pos).getFluidState().getFluid() == Fluids.WATER;
    }

    private boolean isLavaSource(BlockPos pos) {
        return mc.world.getBlockState(pos).getFluidState().getFluid() == Fluids.LAVA;
    }

    // ── Prediction & candidate building ──────────────────────────────────────

    private BlockPos getPredictedPos(LivingEntity entity, int ticks) {
        if (ticks <= 0) return entity.getBlockPos();
        double dx = entity.getX() - entity.lastX;
        double dz = entity.getZ() - entity.lastZ;
        return BlockPos.ofFloored(entity.getEntityPos().add(dx * ticks, 0, dz * ticks));
    }

    private void buildPlaceable(LivingEntity target) {
        BlockPos base        = getPredictedPos(target, predictTicks.get());
        boolean  hasWeb      = hasInHotbar(Items.COBWEB);
        boolean  hasLava     = hasInHotbar(Items.LAVA_BUCKET);
        boolean  targetInWeb = isInCobweb(target);

        // ── 基础 2×2 脚部候选 ───────────────────────────────────────────────
        for (BlockPos pos : getFootQuad(base)) {
            tryAddWeb(pos, targetInWeb);
            // FIX 2 & 4: lava bestFaces now allows cobweb support + air-below support
            tryAddLava(pos, !hasWeb || !hasLava);
        }

        // ── FIX 3: 目标被蜘蛛网困住时，额外添加蜘蛛网岩浆放置候选 ────────────
        if (targetInWeb) {
            addCobwebLavaCandidates(target, base);
        }
    }

    /**
     * 当目标被困在蜘蛛网中时，额外渲染3个可放置岩浆的面：
     *   1. 蜘蛛网正上方（目标头部上方一格）
     *   2. 距离玩家最近的两个水平侧面（蜘蛛网同层的水平相邻位置）
     *
     * 这3个位置以蜘蛛网本身作为支撑面，因此 bestFacesForLavaOnCobweb 必须允许 cobweb 作为支撑。
     */
    private void addCobwebLavaCandidates(LivingEntity target, BlockPos base) {
        // 找到目标所在的蜘蛛网方块位置
        BlockPos webPos = null;
        if (mc.world.getBlockState(target.getBlockPos()).isOf(Blocks.COBWEB)) {
            webPos = target.getBlockPos();
        } else {
            BlockPos headPos = BlockPos.ofFloored(target.getX(), target.getY() + 1, target.getZ());
            if (mc.world.getBlockState(headPos).isOf(Blocks.COBWEB)) {
                webPos = headPos;
            }
        }
        if (webPos == null) return;

        Vec3d playerEye = mc.player.getEyePos();

        // 1. 蜘蛛网正上方 (webPos.up() 是放置位置，支撑面是 webPos 的 UP 面)
        BlockPos above = webPos.up();
        if (isValidForLava(above, true)) {
            // 支撑面是 webPos（蜘蛛网），face = UP
            List<Direction> faces = new ArrayList<>();
            faces.add(Direction.UP); // place above, supported by web below
            if (!lavaRenderPos.contains(above)) {
                lavaRenderPos.add(above);
                lavaRenderFaces.put(above, faces);
            }
        }

        // 2. 蜘蛛网水平最近的两个侧面
        // 候选：North, South, East, West 各相邻位置（与蜘蛛网同高）
        List<Direction> horizontalDirs = Arrays.asList(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

        // 按距离玩家眼睛排序，取最近两个有效的
        horizontalDirs.sort(Comparator.comparingDouble(d -> {
            BlockPos sidePos = webPos.offset(d);
            return Vec3d.ofCenter(sidePos).squaredDistanceTo(playerEye);
        }));

        int added = 0;
        for (Direction d : horizontalDirs) {
            if (added >= 2) break;
            BlockPos sidePos = webPos.offset(d);
            if (!isValidForLava(sidePos, true)) continue;
            // 支撑面是 webPos（蜘蛛网），face 是 d.getOpposite()
            // 意味着 sidePos 被 webPos 支撑，face 方向从 sidePos 看向 webPos
            Direction supportFace = d.getOpposite();
            double distToSupport = Vec3d.ofCenter(webPos).distanceTo(playerEye);
            if (distToSupport > targetRange.get()) continue;

            List<Direction> faces;
            if (lavaRenderPos.contains(sidePos)) {
                // 已有候选，追加面
                faces = lavaRenderFaces.get(sidePos);
                if (faces != null && !faces.contains(supportFace)) {
                    faces.add(supportFace);
                }
            } else {
                faces = new ArrayList<>();
                faces.add(supportFace);
                lavaRenderPos.add(sidePos);
                lavaRenderFaces.put(sidePos, faces);
            }
            added++;
        }
    }

    private boolean isOnGround(LivingEntity entity) {
        BlockPos below = BlockPos.ofFloored(entity.getX(), entity.getY() - 0.1, entity.getZ());
        BlockState state = mc.world.getBlockState(below);
        return !state.isAir() && !state.isReplaceable();
    }

    private boolean isInCobweb(LivingEntity entity) {
        return mc.world.getBlockState(entity.getBlockPos()).isOf(Blocks.COBWEB)
            || mc.world.getBlockState(BlockPos.ofFloored(entity.getX(), entity.getY() + 1, entity.getZ())).isOf(Blocks.COBWEB);
    }

    private List<BlockPos> getFootQuad(BlockPos footPos) {
        List<BlockPos> list = new ArrayList<>();
        int x = footPos.getX(), z = footPos.getZ(), y = footPos.getY();
        list.add(new BlockPos(x,     y, z));
        list.add(new BlockPos(x + 1, y, z));
        list.add(new BlockPos(x,     y, z + 1));
        list.add(new BlockPos(x + 1, y, z + 1));
        return list;
    }

    private void tryAddWeb(BlockPos pos, boolean allowOnCobweb) {
        if (!isValidForWeb(pos)) return;
        List<Direction> faces = bestFacesForWeb(pos, allowOnCobweb);
        if (faces.isEmpty()) return;
        webRenderPos.add(pos);
        webRenderFaces.put(pos, faces);
    }

    private void tryAddLava(BlockPos pos, boolean noFluidRestriction) {
        if (!isValidForLava(pos, noFluidRestriction)) return;
        // FIX 2: allowCobwebSupport=true so lava can be placed on cobweb supports
        List<Direction> faces = bestFacesForLava(pos);
        if (faces.isEmpty()) return;
        lavaRenderPos.add(pos);
        lavaRenderFaces.put(pos, faces);
    }

    /**
     * Web placement faces: cobweb requires solid non-cobweb support
     * (unless target is already stuck, then cobweb support is also allowed).
     */
    private List<Direction> bestFacesForWeb(BlockPos pos, boolean allowCobwebSupport) {
        Vec3d eye = mc.player.getEyePos();
        List<Direction> validFaces = new ArrayList<>();

        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d.getOpposite());
            BlockState supportState = mc.world.getBlockState(support);
            if (supportState.isAir() || supportState.isReplaceable()) continue;
            if (!allowCobwebSupport && supportState.isOf(Blocks.COBWEB)) continue;
            if (Vec3d.ofCenter(support).distanceTo(eye) > targetRange.get()) continue;
            validFaces.add(d);
        }

        validFaces.sort(Comparator.comparingDouble(
            d -> Vec3d.ofCenter(pos.offset(d.getOpposite())).squaredDistanceTo(eye)));
        return validFaces;
    }

    /**
     * FIX 2 & 4: Lava placement faces.
     *
     * Key differences from web:
     *  - Cobweb IS a valid support for lava (to burn it).
     *  - Air below IS valid: lava buckets don't need a solid floor to stay;
     *    we still need a solid neighbour to click on, but DOWN direction
     *    (air below the pos) is now included as a special case using
     *    a virtual "floor click" — we let interactItem handle the fallback.
     *
     * For the air-below case: we add Direction.DOWN to faces even if the
     * block below is air, because placeBlock() will fall back to interactItem
     * which places lava at the cursor position regardless.
     */
    private List<Direction> bestFacesForLava(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        List<Direction> validFaces = new ArrayList<>();

        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d.getOpposite());
            BlockState supportState = mc.world.getBlockState(support);

            // FIX 4: Allow air below — lava bucket can be placed in mid-air
            // by using interactItem. We still add it as a face candidate.
            if (d == Direction.DOWN) {
                // Air below = no solid floor, but lava bucket can still be placed via interactItem.
                // Only add if within range.
                if (Vec3d.ofCenter(pos).distanceTo(eye) <= targetRange.get()) {
                    validFaces.add(Direction.DOWN);
                }
                continue;
            }

            // Standard solid support check (but cobweb IS allowed for lava)
            if (supportState.isAir() || supportState.isReplaceable()) continue;
            // FIX 2: Do NOT skip cobweb — lava can be placed on cobweb faces
            if (Vec3d.ofCenter(support).distanceTo(eye) > targetRange.get()) continue;
            validFaces.add(d);
        }

        validFaces.sort(Comparator.comparingDouble(d -> {
            BlockPos support = pos.offset(d.getOpposite());
            return Vec3d.ofCenter(support).squaredDistanceTo(eye);
        }));
        return validFaces;
    }

    private Direction getBestFace(BlockPos pos, Map<BlockPos, List<Direction>> faceMap) {
        List<Direction> faces = faceMap.get(pos);
        if (faces == null || faces.isEmpty()) return null;
        return faces.get(0);
    }

    // ── Raycast helpers ───────────────────────────────────────────────────────

    private BlockPos getLookedPlacePos(List<BlockPos> candidates) {
        Vec3d cameraPos = mc.player.getCameraPosVec(1.0F);
        Vec3d endPos    = cameraPos.add(mc.player.getRotationVec(1.0F).multiply(targetRange.get()));

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cameraPos, endPos,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player));

        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
            if (candidates.contains(placePos)) return placePos;
        }
        return null;
    }

    private BlockPos getLookedFluidPos() {
        Vec3d cameraPos = mc.player.getCameraPosVec(1.0F);
        Vec3d endPos    = cameraPos.add(mc.player.getRotationVec(1.0F).multiply(targetRange.get()));

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cameraPos, endPos,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.SOURCE_ONLY,
            mc.player));

        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            Fluid fluid = mc.world.getBlockState(hitPos).getFluidState().getFluid();
            if (fluid == Fluids.WATER || fluid == Fluids.LAVA) return hitPos;
        }
        return null;
    }

    // ── Target tracking ───────────────────────────────────────────────────────

    private void updateTarget() {
        if (mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult entityHit
                && entityHit.getEntity() instanceof LivingEntity living) {
            lockedTarget = living;
        }
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    private int getSlot(Item item) {
        if (mc.player.getMainHandStack().isOf(item)) return mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private boolean hasInHotbar(Item item) {
        return getSlot(item) != -1;
    }

    // ── Fluid scan ────────────────────────────────────────────────────────────

    private void scanNearbyFluids() {
        BlockPos playerPos = mc.player.getBlockPos();
        double range = fluidRange.get();
        int r = (int) Math.ceil(range);

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > range * range) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    Fluid fluid = state.getFluidState().getFluid();
                    if (fluid == Fluids.WATER || fluid == Fluids.LAVA) {
                        fluidRenderPos.add(pos);
                    }
                }
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        boolean holdMode  = triggerMode.get() == TriggerMode.HOLD;
        boolean triggered = !holdMode || activateBind.get().isPressed();

        if (triggered) {
            for (BlockPos pos : fluidRenderPos) {
                Fluid fluid = mc.world.getBlockState(pos).getFluidState().getFluid();
                boolean isWater = fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
                if (isWater) {
                    event.renderer.box(pos, waterColor.get(), waterLine.get(), ShapeMode.Both, 0);
                } else {
                    event.renderer.box(pos, fluidLavaColor.get(), fluidLavaLine.get(), ShapeMode.Both, 0);
                }
            }
        }

        // FIX 1: Web render — use aimedWebPos (independent of lava aim)
        for (BlockPos pos : webRenderPos) {
            List<Direction> faces = webRenderFaces.get(pos);
            if (faces == null || faces.isEmpty()) continue;

            boolean isAimed  = pos.equals(aimedWebPos); // FIX: use aimedWebPos directly
            boolean canPlace = hasInHotbar(Items.COBWEB) && isValidForWeb(pos);

            SettingColor side = (triggered && isAimed) ? aimColor.get()
                               : canPlace              ? webColor.get()
                               : new SettingColor(150, 150, 150, 30);
            SettingColor line = (triggered && isAimed) ? aimLine.get()
                               : canPlace              ? webLine.get()
                               : new SettingColor(150, 150, 150, 180);

            for (Direction face : faces) {
                BlockPos support = pos.offset(face.getOpposite());
                renderSupportFace(event, support, face, side, line, 0.0);
            }
        }

        // FIX 1: Lava render — use aimedLavaPos (independent of web aim)
        for (BlockPos pos : lavaRenderPos) {
            List<Direction> faces = lavaRenderFaces.get(pos);
            if (faces == null || faces.isEmpty()) continue;

            boolean isAimed  = pos.equals(aimedLavaPos); // FIX: use aimedLavaPos directly
            boolean canPlace = hasInHotbar(Items.LAVA_BUCKET) && isValidForLava(pos, false);

            SettingColor side = (triggered && isAimed) ? aimColor.get()
                               : canPlace              ? lavaColor.get()
                               : new SettingColor(150, 150, 150, 30);
            SettingColor line = (triggered && isAimed) ? aimLine.get()
                               : canPlace              ? lavaLine.get()
                               : new SettingColor(150, 150, 150, 180);

            for (Direction face : faces) {
                BlockPos support = pos.offset(face.getOpposite());
                renderSupportFace(event, support, face, side, line, 0.0);
            }
        }
    }

    // ── Thin-face renderer ────────────────────────────────────────────────────

    private void renderSupportFace(Render3DEvent event, BlockPos support, Direction face,
                                   SettingColor side, SettingColor line, double inset) {
        final double T = 0.02;
        double bx = support.getX(), by = support.getY(), bz = support.getZ();
        double x1, y1, z1, x2, y2, z2;

        switch (face) {
            case UP    -> { x1 = bx+inset;   y1 = by+1-T;       z1 = bz+inset;   x2 = bx+1-inset; y2 = by+1+T-inset; z2 = bz+1-inset; }
            case DOWN  -> { x1 = bx+inset;   y1 = by-T+inset;   z1 = bz+inset;   x2 = bx+1-inset; y2 = by+T;         z2 = bz+1-inset; }
            case NORTH -> { x1 = bx+inset;   y1 = by+inset;     z1 = bz-T+inset; x2 = bx+1-inset; y2 = by+1-inset;   z2 = bz+T;       }
            case SOUTH -> { x1 = bx+inset;   y1 = by+inset;     z1 = bz+1-T;     x2 = bx+1-inset; y2 = by+1-inset;   z2 = bz+1+T-inset; }
            case WEST  -> { x1 = bx-T+inset; y1 = by+inset;     z1 = bz+inset;   x2 = bx+T;       y2 = by+1-inset;   z2 = bz+1-inset; }
            case EAST  -> { x1 = bx+1-T;     y1 = by+inset;     z1 = bz+inset;   x2 = bx+1+T-inset; y2 = by+1-inset; z2 = bz+1-inset; }
            default    -> { return; }
        }
        event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, ShapeMode.Both, 0);
    }
}
