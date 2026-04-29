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
import net.minecraft.entity.player.PlayerEntity;
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
import java.util.stream.Collectors;

public class AutoWeb extends Module {

    // ── 触发模式 ──────────────────────────────────────────────────────────────

    private enum TriggerMode { HOLD, ALWAYS }

    // ── 候选放置点动作类型 ────────────────────────────────────────────────────

    /** 候选放置点的期望动作：放网或放岩浆 */
    private enum PlaceType { WEB, LAVA }

    /**
     * 单个候选放置点，携带所有决策所需信息。
     * priority 越高越优先处理。
     */
    private static class PlaceCandidate {
        final BlockPos        pos;
        final List<Direction> faces;
        final PlaceType       type;
        final LivingEntity    source;   // 产生这个候选点的目标实体
        final int             priority; // 越大越优先

        PlaceCandidate(BlockPos pos, List<Direction> faces,
                       PlaceType type, LivingEntity source, int priority) {
            this.pos      = pos;
            this.faces    = faces;
            this.type     = type;
            this.source   = source;
            this.priority = priority;
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

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
        .description("扫描目标 + 放置作用距离")
        .defaultValue(10).min(1).max(20)
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



    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("在聊天框输出详细日志")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug2 = sgGeneral.add(new BoolSetting.Builder()
        .name("debug2")
        .description("开启后渲染所有候选放置面")
        .defaultValue(false)
        .build());

    // ── 渲染颜色 ──────────────────────────────────────────────────────────────

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

    // ── 运行时状态 ────────────────────────────────────────────────────────────

    /** 本 tick 所有候选放置点（按 priority 降序排列） */
    private final List<PlaceCandidate> allCandidates = new ArrayList<>();

    /** 当前瞄准的 web / lava 候选点（渲染高亮用） */
    private BlockPos aimedWebPos  = null;
    private BlockPos aimedLavaPos = null;
    private BlockPos aimedFluid   = null;

    /** 统一行动逻辑用的字段（从上面两个派生） */
    private PlaceCandidate aimedCandidate = null;

    /** 液体渲染列表 */
    private final List<BlockPos> fluidRenderPos = new ArrayList<>();

    private int     cd            = 0;
    private int     prevSlot      = -1;
    private int     swapBackTimer = 0;
    private boolean wasTriggered  = false;

    // 水桶二步骤状态
    private enum WaterPlaceState { NONE, PLACED }
    private WaterPlaceState waterPlaceState = WaterPlaceState.NONE;
    private BlockPos        pendingScoopPos = null;

    // ── 构造 ──────────────────────────────────────────────────────────────────

    public AutoWeb() {
        super(Categories.Combat, "auto-web", "Multi-target cobweb / lava placement.");
    }

    @Override public void onActivate()   { if (debug.get()) info("模块已开启，开始扫描范围内所有目标。"); }
    @Override public void onDeactivate() {
        clearState();
        prevSlot = -1; swapBackTimer = 0; wasTriggered = false;
        waterPlaceState = WaterPlaceState.NONE; pendingScoopPos = null;
        if (debug.get()) info("模块已关闭。");
    }

    private void clearState() {
        allCandidates.clear();
        fluidRenderPos.clear();
        aimedWebPos = null; aimedLavaPos = null; aimedFluid = null; aimedCandidate = null;
    }

    // ── Tick 主循环 ───────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        // 换格倒计时
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

        // ── 每 tick 全量重建候选列表 ─────────────────────────────────────────
        clearState();
        scanNearbyFluids();
        buildAllCandidates(); // 扫描范围内所有目标

        // ── 瞄准点检测 ───────────────────────────────────────────────────────
        aimedFluid    = getLookedFluidPos();
        aimedWebPos   = getLookedCandidatePos(PlaceType.WEB);
        aimedLavaPos  = getLookedCandidatePos(PlaceType.LAVA);

        // 瞄准候选对象（供动作逻辑用）：优先 lava aim（因为 lava > web in action priority），
        // 但如果没有 lava aim 才退到 web aim。
        aimedCandidate = findCandidate(aimedLavaPos, PlaceType.LAVA);
        if (aimedCandidate == null) aimedCandidate = findCandidate(aimedWebPos, PlaceType.WEB);

        // Debug：显示瞄准位置得分 & 优先结果
        if (debug.get() && triggered) {
            // 空桶液体瞄准检测
            boolean hasBucket = hasInHotbar(Items.BUCKET);
            if (aimedFluid != null) {
                Fluid f = mc.world.getBlockState(aimedFluid).getFluidState().getFluid();
                boolean isSource = mc.world.getBlockState(aimedFluid).getFluidState().isStill();
                String fluidName = (f == Fluids.WATER) ? "水" : (f == Fluids.LAVA) ? "岩浆" : "未知";
                if (isSource && hasBucket) {
                    info("[瞄准-液体] 位置: " + aimedFluid.toShortString()
                        + " | 液体: " + fluidName
                        + " | 得分: 999 (空桶优先)");
                } else {
                    info("[瞄准-液体] 位置: " + aimedFluid.toShortString()
                        + " | 液体: " + fluidName + (isSource ? "" : "(非母体)")
                        + " | 得分: -1 (不放置)");
                }
            }
            if (aimedCandidate != null) {
                info("[瞄准] 位置: " + aimedCandidate.pos.toShortString()
                    + " | 类型: " + aimedCandidate.type
                    + " | 得分: " + aimedCandidate.priority);
            } else {
                info("[瞄准] 位置: 无 | 类型: 无 | 得分: -1 (不放置)");
            }
            if (!allCandidates.isEmpty()) {
                PlaceCandidate best = allCandidates.get(0);
                info("[优先] 位置: " + best.pos.toShortString()
                    + " | 类型: " + best.type
                    + " | 得分: " + best.priority
                    + " | 目标: " + (best.source != null ? best.source.getName().getString() : "null"));
            } else {
                info("[优先] 位置: 无 | 类型: 无 | 得分: -1 (不放置)");
            }
        }

        // 松键时换回
        if (wasTriggered && !triggered && prevSlot != -1) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1; swapBackTimer = 0;
        }
        wasTriggered = triggered;

        if (!triggered || cd > 0) return;

        // ── 执行动作 ─────────────────────────────────────────────────────────
        executeAction();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  多目标扫描 & 优先级计算
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 扫描范围内所有玩家，为每个目标建立候选放置点。
     *
     * 优先级分数规则（越大越优先处理）：
     *   被网住  → priority 100  (LAVA 烧网)
     *   着火    → priority 50   (WEB)
     *   在空中  → priority 60   (WEB)
     *   在地面  → priority 50   (LAVA)
     *   距离越近 → priority += (range - dist) * 2  (近的稍微加分)
     *
     * 库存覆盖规则：
     *   只有岩浆桶，没有蜘蛛网 → 所有目标强制 LAVA（无视空中逻辑）
     *   只有蜘蛛网，没有岩浆桶 → 所有目标强制 WEB
     */
    private void buildAllCandidates() {
        boolean hasWeb  = hasInHotbar(Items.COBWEB);
        boolean hasLava = hasInHotbar(Items.LAVA_BUCKET);
        // 只有岩浆无网 → 强制 LAVA；只有网无岩浆 → 强制 WEB
        boolean forceLava = hasLava && !hasWeb;
        boolean forceWeb  = hasWeb  && !hasLava;

        Vec3d eye = mc.player.getEyePos();
        double range = targetRange.get();

        for (PlayerEntity entity : mc.world.getEntitiesByClass(
                PlayerEntity.class,
                mc.player.getBoundingBox().expand(range),
                e -> isValidTarget(e, range))) {

            // ── 判断这个目标的首选动作类型 ──────────────────────────────────
            boolean inCobweb = isInCobweb(entity);
            boolean onFire   = entity.isOnFire();
            boolean inAir    = !isOnGround(entity);

            PlaceType preferredType;
            int basePriority;

            if (inCobweb) {
                // 被网住 → 岩浆最优先
                preferredType = PlaceType.LAVA;
                basePriority  = 100;
            } else if (onFire) {
                // 着火 → Web
                preferredType = PlaceType.WEB;
                basePriority  = 50;
            } else if (inAir) {
                // 空中 → Web（除非强制岩浆）
                preferredType = forceLava ? PlaceType.LAVA : PlaceType.WEB;
                basePriority  = forceLava ? 55 : 60;
            } else {
                // 地面 → LAVA（除非强制Web）
                preferredType = forceWeb ? PlaceType.WEB : PlaceType.LAVA;
                basePriority  = forceWeb ? 45 : 50;
            }

            // 强制覆盖
            if (forceWeb  && !inCobweb && !onFire) preferredType = PlaceType.WEB;
            // forceLava 已在上面处理

            // 距离加分（最多 +14）
            double dist = entity.distanceTo(mc.player);
            int priority = basePriority + (int) ((range - dist) * 2);

            // ── 使用该目标当前位置 ────────────────────────────────────────────
            BlockPos base = entity.getBlockPos();

            // ── 为该目标建立放置候选点 ────────────────────────────────────────
            buildCandidatesForTarget(entity, base, preferredType, priority, inCobweb);
        }

        // 按 priority 降序排列，相同 priority 按距离玩家眼睛升序
        allCandidates.sort(Comparator
            .comparingInt((PlaceCandidate c) -> c.priority).reversed()
            .thenComparingDouble(c -> Vec3d.ofCenter(c.pos).squaredDistanceTo(eye)));

        if (debug.get() && !allCandidates.isEmpty()) {
            info("扫描到 " + allCandidates.size() + " 个候选放置点，最高优先级: "
                + allCandidates.get(0).priority
                + " (" + allCandidates.get(0).type + ")"
                + " @ " + allCandidates.get(0).pos.toShortString());
        }
    }

    /**
     * 为单个目标建立所有候选放置点（脚部 2×2 + 被网时的额外3个岩浆点）。
     */
    private void buildCandidatesForTarget(LivingEntity entity, BlockPos base,
                                          PlaceType type, int priority, boolean inCobweb) {
        boolean noFluidRestriction = (type == PlaceType.LAVA);

        for (BlockPos pos : getFootQuad(base)) {
            if (type == PlaceType.WEB) {
                tryAddCandidate(pos, PlaceType.WEB, entity, priority, inCobweb, false);
            } else {
                tryAddCandidate(pos, PlaceType.LAVA, entity, priority, inCobweb, noFluidRestriction);
            }
            // 始终为另一种类型生成次级候选（优先级减半，颜色会区分）
            if (type == PlaceType.WEB) {
                tryAddCandidate(pos, PlaceType.LAVA, entity, priority / 2, inCobweb, true);
            } else {
                tryAddCandidate(pos, PlaceType.WEB, entity, priority / 2, inCobweb, inCobweb);
            }
        }

        // 被网住时：额外3个岩浆候选（上方 + 最近2个侧面）
        if (inCobweb) {
            addCobwebLavaCandidates(entity, priority, base);
        }
    }

    private void tryAddCandidate(BlockPos pos, PlaceType type, LivingEntity source,
                                 int priority, boolean allowCobwebSupport, boolean noFluidRestriction) {
        // 有效性检查
        if (type == PlaceType.WEB  && !isValidForWeb(pos))             return;
        if (type == PlaceType.LAVA && !isValidForLava(pos, noFluidRestriction)) return;

        List<Direction> faces = (type == PlaceType.WEB)
            ? bestFacesForWeb(pos, allowCobwebSupport)
            : bestFacesForLava(pos);
        if (faces.isEmpty()) return;

        // 岩浆额外加分：不存在玩家的面 → +1分
        if (type == PlaceType.LAVA) {
            for (Direction d : Direction.values()) {
                BlockPos neighbor = pos.offset(d);
                boolean hasPlayer = false;
                for (PlayerEntity p : mc.world.getPlayers()) {
                    if (p == mc.player) continue;
                    if (p.getBlockPos().equals(neighbor) ||
                        BlockPos.ofFloored(p.getX(), p.getY() + 1, p.getZ()).equals(neighbor)) {
                        hasPlayer = true;
                        break;
                    }
                }
                if (!hasPlayer) priority += 1;
            }
        }

        // 去重：如果同一个 pos + type 已存在，保留 priority 更高的那个
        for (int i = 0; i < allCandidates.size(); i++) {
            PlaceCandidate existing = allCandidates.get(i);
            if (existing.pos.equals(pos) && existing.type == type) {
                if (priority > existing.priority) {
                    allCandidates.set(i, new PlaceCandidate(pos, faces, type, source, priority));
                }
                return;
            }
        }
        allCandidates.add(new PlaceCandidate(pos, faces, type, source, priority));
    }

    /**
     * 被网住时额外添加：蜘蛛网上方 + 距玩家最近的2个水平侧面。
     */
    private void addCobwebLavaCandidates(LivingEntity entity, int priority, BlockPos base) {
        BlockPos webPos = null;
        if (mc.world.getBlockState(entity.getBlockPos()).isOf(Blocks.COBWEB)) {
            webPos = entity.getBlockPos();
        } else {
            BlockPos head = BlockPos.ofFloored(entity.getX(), entity.getY() + 1, entity.getZ());
            if (mc.world.getBlockState(head).isOf(Blocks.COBWEB)) webPos = head;
        }
        if (webPos == null) return;

        Vec3d playerEye = mc.player.getEyePos();
        int cobwebPriority = priority + 20; // 蜘蛛网上的岩浆点额外加分

        // 上方
        BlockPos above = webPos.up();
        if (isValidForLava(above, true)) {
            List<Direction> faces = new ArrayList<>();
            faces.add(Direction.UP);
            // 去重判断
            boolean dup = false;
            for (PlaceCandidate c : allCandidates) {
                if (c.pos.equals(above) && c.type == PlaceType.LAVA) { dup = true; break; }
            }
            if (!dup) allCandidates.add(new PlaceCandidate(above, faces, PlaceType.LAVA, entity, cobwebPriority));
        }

        // 最近2个水平侧面
        final BlockPos finalWebPos = webPos;
        List<Direction> horizontals = Arrays.asList(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        horizontals.sort(Comparator.comparingDouble(
            d -> Vec3d.ofCenter(finalWebPos.offset(d)).squaredDistanceTo(playerEye)));

        int added = 0;
        for (Direction d : horizontals) {
            if (added >= 2) break;
            BlockPos sidePos = webPos.offset(d);
            if (!isValidForLava(sidePos, true)) continue;
            if (Vec3d.ofCenter(webPos).distanceTo(playerEye) > targetRange.get()) continue;

            Direction supportFace = d.getOpposite();
            boolean dup = false;
            for (PlaceCandidate c : allCandidates) {
                if (c.pos.equals(sidePos) && c.type == PlaceType.LAVA) { dup = true; break; }
            }
            if (!dup) {
                List<Direction> faces = new ArrayList<>();
                faces.add(supportFace);
                allCandidates.add(new PlaceCandidate(sidePos, faces, PlaceType.LAVA, entity, cobwebPriority));
            }
            added++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  动作执行
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 动作优先级（高 → 低）：
     *   1. 空桶收水（最高，不论任何情况）
     *   2. 水桶放水换空桶（次之）
     *   3. 空桶收岩浆
     *   4. 手持物匹配 aimedCandidate → 直接放
     *   5. 按 allCandidates 优先级列表顺序，取第一个有对应物品的候选点放置
     */
    private void executeAction() {
        boolean hasLava        = hasInHotbar(Items.LAVA_BUCKET);
        boolean hasWeb         = hasInHotbar(Items.COBWEB);
        boolean hasBucket      = hasInHotbar(Items.BUCKET);
        boolean hasWaterBucket = hasInHotbar(Items.WATER_BUCKET);

        boolean isWaterAimed = aimedFluid != null && isWaterSource(aimedFluid);
        boolean isLavaAimed  = aimedFluid != null && isLavaSource(aimedFluid);

        // 0. 等待收水二步骤
        if (waterPlaceState == WaterPlaceState.PLACED && pendingScoopPos != null) {
            if (isWaterSource(pendingScoopPos) && hasBucket) {
                scoopFluid(pendingScoopPos, "收水(二步)");
                waterPlaceState = WaterPlaceState.NONE; pendingScoopPos = null;
            } else {
                waterPlaceState = WaterPlaceState.NONE; pendingScoopPos = null;
            }
            return;
        }

        // 1. 空桶收水 ─────────────────────────────────────────────────────────
        if (isWaterAimed && hasBucket) {
            scoopFluid(aimedFluid, "收水"); return;
        }
        // 2. 水桶换空桶
        if (isWaterAimed && hasWaterBucket) {
            placeWaterForBucket(aimedFluid); return;
        }
        // 3. 空桶收岩浆
        if (isLavaAimed && hasBucket) {
            scoopFluid(aimedFluid, "收岩浆"); return;
        }

        // 4. 手持物匹配 aimedCandidate → 直接放（手持优先），但瞄到液体时不放
        if (aimedCandidate != null && aimedFluid == null) {
            Item mainHand = mc.player.getMainHandStack().getItem();
            boolean mainMatchesAim =
                (aimedCandidate.type == PlaceType.WEB  && mainHand == Items.COBWEB)      ||
                (aimedCandidate.type == PlaceType.LAVA && mainHand == Items.LAVA_BUCKET);

            if (mainMatchesAim) {
                placeCandidate(aimedCandidate); return;
            }
            // 瞄准了某个位置但手里不是对应物品 → 也直接放（自动换格）
            if ((aimedCandidate.type == PlaceType.WEB  && hasWeb) ||
                (aimedCandidate.type == PlaceType.LAVA && hasLava)) {
                placeCandidate(aimedCandidate); return;
            }
        }

        // 5. 按优先级列表自动选择第一个可执行的候选点（瞄到液体时不自动放）
        if (aimedFluid == null) {
            for (PlaceCandidate candidate : allCandidates) {
                boolean canDo = (candidate.type == PlaceType.WEB  && hasWeb)
                             || (candidate.type == PlaceType.LAVA && hasLava);
                if (canDo) {
                    placeCandidate(candidate); return;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  放置 / 收取 实现
    // ═══════════════════════════════════════════════════════════════════════════

    private void placeCandidate(PlaceCandidate candidate) {
        Item item = (candidate.type == PlaceType.WEB) ? Items.COBWEB : Items.LAVA_BUCKET;
        String name = (candidate.type == PlaceType.WEB) ? "蜘蛛网" : "岩浆";
        placeBlock(candidate.pos, candidate.faces, item, name);
    }

    private void placeBlock(BlockPos targetPos, List<Direction> faces, Item item, String name) {
        if (targetPos == null || faces == null || faces.isEmpty()) return;
        int slot = getSlot(item);
        if (slot == -1) {
            if (debug.get()) warning("放置" + name + "失败：背包没有该物品");
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
            if (!result.isAccepted()) result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

            if (result.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                if (debug.get()) info(">> " + name + " 放置成功（面: " + face + "）！");
                cd = delay.get(); swapBackTimer = 2; return;
            }
        }

        if (debug.get()) warning(">> " + name + " 所有面均失败！");
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
        if (!result.isAccepted()) result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        if (result.isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (debug.get()) info(">> " + name + " 成功！");
            cd = delay.get(); swapBackTimer = 2;
        } else {
            if (debug.get()) warning(">> " + name + " 失败！");
            swapBackTimer = 1;
        }
    }

    private void placeWaterForBucket(BlockPos targetPos) {
        if (targetPos == null) return;
        int slot = getSlot(Items.WATER_BUCKET);
        if (slot == -1) return;
        if (debug.get()) info("放水换空桶 @ " + targetPos.toShortString());

        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Vec3d hitVec = Vec3d.ofCenter(targetPos);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        if (!result.isAccepted()) result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        if (result.isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            waterPlaceState = WaterPlaceState.PLACED;
            pendingScoopPos = targetPos;
            cd = delay.get(); swapBackTimer = 2;
        } else {
            swapBackTimer = 1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  实体 / 方块 判断工具
    // ═══════════════════════════════════════════════════════════════════════════

    /** 判断是否为有效攻击目标（排除自身、非玩家队友等） */
    private boolean isValidTarget(LivingEntity entity, double range) {
        if (entity == mc.player) return false;
        if (!entity.isAlive()) return false;
        if (entity instanceof PlayerEntity p && p.isCreative()) return false;
        return entity.distanceTo(mc.player) <= range;
    }

    private boolean isValidForWeb(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean isValidForLava(BlockPos pos, boolean noFluidRestriction) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isReplaceable()) return false;
        if (noFluidRestriction) return true;
        Fluid f = state.getFluidState().getFluid();
        return f != Fluids.WATER && f != Fluids.FLOWING_WATER;
    }

    private boolean isWaterSource(BlockPos pos) { return mc.world.getBlockState(pos).getFluidState().getFluid() == Fluids.WATER; }
    private boolean isLavaSource(BlockPos pos)  { return mc.world.getBlockState(pos).getFluidState().getFluid() == Fluids.LAVA;  }

    private boolean isOnGround(LivingEntity entity) {
        BlockPos below = BlockPos.ofFloored(entity.getX(), entity.getY() - 0.1, entity.getZ());
        BlockState state = mc.world.getBlockState(below);
        return !state.isAir() && !state.isReplaceable();
    }

    private boolean isInCobweb(LivingEntity entity) {
        return mc.world.getBlockState(entity.getBlockPos()).isOf(Blocks.COBWEB)
            || mc.world.getBlockState(BlockPos.ofFloored(entity.getX(), entity.getY() + 1, entity.getZ())).isOf(Blocks.COBWEB);
    }

    // ── 面计算 ────────────────────────────────────────────────────────────────

    private List<Direction> bestFacesForWeb(BlockPos pos, boolean allowCobwebSupport) {
        Vec3d eye = mc.player.getEyePos();
        List<Direction> valid = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d.getOpposite());
            BlockState ss = mc.world.getBlockState(support);
            if (ss.isAir() || ss.isReplaceable()) continue;
            if (!allowCobwebSupport && ss.isOf(Blocks.COBWEB)) continue;
            if (Vec3d.ofCenter(support).distanceTo(eye) > targetRange.get()) continue;
            valid.add(d);
        }
        valid.sort(Comparator.comparingDouble(d -> Vec3d.ofCenter(pos.offset(d.getOpposite())).squaredDistanceTo(eye)));
        return valid;
    }

    private List<Direction> bestFacesForLava(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        List<Direction> valid = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d.getOpposite());
            BlockState ss = mc.world.getBlockState(support);
            if (d == Direction.DOWN) {
                if (Vec3d.ofCenter(pos).distanceTo(eye) <= targetRange.get()) valid.add(Direction.DOWN);
                continue;
            }
            if (ss.isAir() || ss.isReplaceable()) continue;
            // 岩浆允许蜘蛛网作为支撑面
            if (Vec3d.ofCenter(support).distanceTo(eye) > targetRange.get()) continue;
            valid.add(d);
        }
        valid.sort(Comparator.comparingDouble(d -> Vec3d.ofCenter(pos.offset(d.getOpposite())).squaredDistanceTo(eye)));
        return valid;
    }

    // ── 脚部四格 ─────────────────────────────────────────────────────────────

    private List<BlockPos> getFootQuad(BlockPos fp) {
        return Arrays.asList(
            new BlockPos(fp.getX(),     fp.getY(), fp.getZ()),
            new BlockPos(fp.getX() + 1, fp.getY(), fp.getZ()),
            new BlockPos(fp.getX(),     fp.getY(), fp.getZ() + 1),
            new BlockPos(fp.getX() + 1, fp.getY(), fp.getZ() + 1));
    }

    // ── Raycast ───────────────────────────────────────────────────────────────

    /**
     * 玩家当前瞄准的、在 allCandidates 中对应 type 的候选点位置。
     */
    private BlockPos getLookedCandidatePos(PlaceType type) {
        Vec3d cam = mc.player.getCameraPosVec(1.0F);
        Vec3d end = cam.add(mc.player.getRotationVec(1.0F).multiply(targetRange.get()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cam, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
        for (PlaceCandidate c : allCandidates) {
            if (c.pos.equals(placePos) && c.type == type) return placePos;
        }
        return null;
    }

    private BlockPos getLookedFluidPos() {
        Vec3d cam = mc.player.getCameraPosVec(1.0F);
        Vec3d end = cam.add(mc.player.getRotationVec(1.0F).multiply(targetRange.get()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            cam, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        Fluid f = mc.world.getBlockState(p).getFluidState().getFluid();
        return (f == Fluids.WATER || f == Fluids.LAVA) ? p : null;
    }

    /** 在 allCandidates 中查找对应 pos + type 的候选 */
    private PlaceCandidate findCandidate(BlockPos pos, PlaceType type) {
        if (pos == null) return null;
        for (PlaceCandidate c : allCandidates) {
            if (c.pos.equals(pos) && c.type == type) return c;
        }
        return null;
    }

    // ── 液体扫描 ──────────────────────────────────────────────────────────────

    private void scanNearbyFluids() {
        BlockPos pp = mc.player.getBlockPos();
        double range = fluidRange.get();
        int r = (int) Math.ceil(range);
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            BlockPos pos = pp.add(dx, dy, dz);
            if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > range * range) continue;
            Fluid f = mc.world.getBlockState(pos).getFluidState().getFluid();
            if (f == Fluids.WATER || f == Fluids.LAVA) fluidRenderPos.add(pos);
        }
    }

    // ── 背包工具 ──────────────────────────────────────────────────────────────

    private int getSlot(Item item) {
        if (mc.player.getMainHandStack().isOf(item)) return mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }
    private boolean hasInHotbar(Item item) { return getSlot(item) != -1; }

    // ═══════════════════════════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        boolean triggered = triggerMode.get() == TriggerMode.ALWAYS
                         || activateBind.get().isPressed();

        // 液体覆盖（触发时始终渲染，不受 debug2 影响）
        if (triggered) {
            for (BlockPos pos : fluidRenderPos) {
                Fluid f = mc.world.getBlockState(pos).getFluidState().getFluid();
                boolean isSource = mc.world.getBlockState(pos).getFluidState().isStill();
                // 只渲染母体液体方块
                if (!isSource) continue;
                boolean isWater = f == Fluids.WATER || f == Fluids.FLOWING_WATER;
                event.renderer.box(pos,
                    isWater ? waterColor.get()     : fluidLavaColor.get(),
                    isWater ? waterLine.get()      : fluidLavaLine.get(),
                    ShapeMode.Both, 0);
            }
        }

        // 候选放置点渲染（仅开启 debug2 时渲染）
        if (debug2.get()) {
            for (PlaceCandidate candidate : allCandidates) {
                boolean isWebType  = candidate.type == PlaceType.WEB;
                boolean isAimed    = isWebType
                    ? candidate.pos.equals(aimedWebPos)
                    : candidate.pos.equals(aimedLavaPos);
                boolean canPlace   = isWebType
                    ? hasInHotbar(Items.COBWEB)       && isValidForWeb(candidate.pos)
                    : hasInHotbar(Items.LAVA_BUCKET)  && isValidForLava(candidate.pos, false);

                SettingColor side, line;
                if (triggered && isAimed) {
                    side = aimColor.get(); line = aimLine.get();
                } else if (canPlace) {
                    side = isWebType ? webColor.get()  : lavaColor.get();
                    line = isWebType ? webLine.get()   : lavaLine.get();
                } else {
                    side = new SettingColor(150, 150, 150, 30);
                    line = new SettingColor(150, 150, 150, 180);
                }

                for (Direction face : candidate.faces) {
                    BlockPos support = candidate.pos.offset(face.getOpposite());
                    renderSupportFace(event, support, face, side, line);
                }
            }
        }
    }

    private void renderSupportFace(Render3DEvent event, BlockPos support, Direction face,
                                   SettingColor side, SettingColor line) {
        final double T = 0.02, I = 0.0;
        double bx = support.getX(), by = support.getY(), bz = support.getZ();
        double x1, y1, z1, x2, y2, z2;
        switch (face) {
            case UP    -> { x1=bx+I;   y1=by+1-T;   z1=bz+I;   x2=bx+1-I; y2=by+1+T-I; z2=bz+1-I; }
            case DOWN  -> { x1=bx+I;   y1=by-T+I;   z1=bz+I;   x2=bx+1-I; y2=by+T;      z2=bz+1-I; }
            case NORTH -> { x1=bx+I;   y1=by+I;     z1=bz-T+I; x2=bx+1-I; y2=by+1-I;    z2=bz+T;   }
            case SOUTH -> { x1=bx+I;   y1=by+I;     z1=bz+1-T; x2=bx+1-I; y2=by+1-I;    z2=bz+1+T-I; }
            case WEST  -> { x1=bx-T+I; y1=by+I;     z1=bz+I;   x2=bx+T;   y2=by+1-I;    z2=bz+1-I; }
            case EAST  -> { x1=bx+1-T; y1=by+I;     z1=bz+I;   x2=bx+1+T-I; y2=by+1-I;  z2=bz+1-I; }
            default    -> { return; }
        }
        event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, ShapeMode.Both, 0);
    }
}
