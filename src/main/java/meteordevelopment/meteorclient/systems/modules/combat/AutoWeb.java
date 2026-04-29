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
        .description("目标放置/收集的作用距离")
        .defaultValue(7)
        .min(1).max(12)
        .build());

    private final Setting<Double> fluidRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("fluid-range")
        .description("附近液体方块的渲染距离（以玩家为中心）")
        .defaultValue(5)
        .min(1).max(12)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .defaultValue(3)
        .min(0).max(20)
        .build());

    private final Setting<Integer> webPredictTicks = sgGeneral.add(new IntSetting.Builder()
        .name("web-predict-ticks")
        .description("蜘蛛网放置对目标移动进行预测")
        .defaultValue(2)
        .min(0).max(10)
        .build());

    private final Setting<Integer> lavaPredictTicks = sgGeneral.add(new IntSetting.Builder()
        .name("lava-predict-ticks")
        .description("岩浆放置对目标移动进行预测")
        .defaultValue(1)
        .min(0).max(10)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("在聊天框输出详细的判断和放置日志，用于排查放不出的原因")
        .defaultValue(true)
        .build());

    // Render colors
    private final Setting<SettingColor> webColor      = sgRender.add(new ColorSetting.Builder().name("web-color").defaultValue(new SettingColor(0, 200, 255, 40)).build());
    private final Setting<SettingColor> webLine       = sgRender.add(new ColorSetting.Builder().name("web-line").defaultValue(new SettingColor(0, 200, 255, 255)).build());
    private final Setting<SettingColor> lavaColor     = sgRender.add(new ColorSetting.Builder().name("lava-color").defaultValue(new SettingColor(255, 80, 0, 40)).build());
    private final Setting<SettingColor> lavaLine      = sgRender.add(new ColorSetting.Builder().name("lava-line").defaultValue(new SettingColor(255, 80, 0, 255)).build());
    private final Setting<SettingColor> aimColor      = sgRender.add(new ColorSetting.Builder().name("aim-color").defaultValue(new SettingColor(0, 255, 0, 60)).build());
    private final Setting<SettingColor> aimLine       = sgRender.add(new ColorSetting.Builder().name("aim-line").defaultValue(new SettingColor(0, 255, 0, 255)).build());
    private final Setting<SettingColor> waterColor    = sgRender.add(new ColorSetting.Builder().name("water-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> waterLine     = sgRender.add(new ColorSetting.Builder().name("water-line").defaultValue(new SettingColor(0, 100, 255, 255)).build());
    private final Setting<SettingColor> fluidLavaColor= sgRender.add(new ColorSetting.Builder().name("fluid-lava-color").defaultValue(new SettingColor(255, 60, 0, 40)).build());
    private final Setting<SettingColor> fluidLavaLine = sgRender.add(new ColorSetting.Builder().name("fluid-lava-line").defaultValue(new SettingColor(255, 60, 0, 255)).build());

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<BlockPos> webRenderPos  = new ArrayList<>();
    private final Map<BlockPos, List<Direction>> webRenderFaces  = new HashMap<>();
    private final List<BlockPos> lavaRenderPos = new ArrayList<>();
    private final Map<BlockPos, List<Direction>> lavaRenderFaces = new HashMap<>();
    private final List<BlockPos> fluidRenderPos = new ArrayList<>();

    // aimed split: which list does the current aim target belong to?
    private BlockPos aimedPos  = null;
    private boolean  aimedIsWeb  = false;  // true=web candidate, false=lava candidate
    private BlockPos aimedFluid = null;    // fluid scoop target (separate from place target)

    private int cd = 0;
    private LivingEntity lockedTarget = null;

    private int  prevSlot       = -1;
    private int  swapBackTimer  = 0;
    private boolean wasTriggered = false;

    private enum ActionType { NONE, PLACE_WEB, PLACE_LAVA, SCOOP_WATER, SCOOP_LAVA }

    // ── Constructor ───────────────────────────────────────────────────────────

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
        if (debug.get()) info("模块已关闭。清空所有状态。");
    }

    private void clearRender() {
        webRenderPos.clear();
        webRenderFaces.clear();
        lavaRenderPos.clear();
        lavaRenderFaces.clear();
        fluidRenderPos.clear();
        aimedPos   = null;
        aimedIsWeb = false;
        aimedFluid = null;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        // Swap-back timer
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

        // Rebuild everything fresh each tick
        clearRender();
        updateTarget();
        scanNearbyFluids();

        // Build placement candidates if we have a live target in range
        if (lockedTarget != null && lockedTarget.isAlive()
                && lockedTarget.distanceTo(mc.player) <= targetRange.get()) {
            buildPlaceable(lockedTarget);
        } else {
            lockedTarget = null;
        }

        // ── Determine aimed position ─────────────────────────────────────────
        // Priority for aimed:
        //   1. Fluid scoop (water bucket)    — highest
        //   2. Web placement candidate
        //   3. Lava placement candidate
        // We track fluid aim separately so we never lose the place-aimed pos.

        aimedFluid = getLookedFluidPos();

        // Web aim: raycast ignoring fluids, look for block face → offset → in webRenderPos
        BlockPos webAim  = getLookedPlacePos(webRenderPos);
        BlockPos lavaAim = getLookedPlacePos(lavaRenderPos);

        if (webAim != null) {
            aimedPos   = webAim;
            aimedIsWeb = true;
        } else if (lavaAim != null) {
            aimedPos   = lavaAim;
            aimedIsWeb = false;
        }

        // Swap-back on key release
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

        // ── Choose action ─────────────────────────────────────────────────────
        //
        // PRIORITY (high → low):
        //   1. SCOOP_WATER  — empty bucket + looking at water source
        //   2. PLACE_LAVA on cobweb — lava bucket + aimedPos is a web candidate whose
        //                             support block is already a cobweb
        //   3. SCOOP_LAVA   — empty bucket + looking at lava source
        //   4. Current main-hand item (if it matches what we need)
        //   5. Cobweb if aimedPos in webRenderPos and have cobweb
        //   6. Lava  if aimedPos in lavaRenderPos and have lava bucket
        //
        // Note: "web candidate" and "lava candidate" sets are built from *different*
        // prediction ticks, so a position can appear in both; we honour which list
        // the player is currently aiming at (aimedIsWeb).

        Item mainHand = mc.player.getMainHandStack().getItem();

        boolean canWeb       = hasInHotbar(Items.COBWEB);
        boolean canLava      = hasInHotbar(Items.LAVA_BUCKET);
        boolean canBucket    = hasInHotbar(Items.BUCKET);

        // Fluid checks at the fluid-aimed pos
        boolean isWaterAimed = aimedFluid != null && isWaterSource(aimedFluid);
        boolean isLavaAimed  = aimedFluid != null && isLavaSource(aimedFluid);

        // Check if the current aimed placement pos sits on a cobweb support
        boolean aimOnCobweb = false;
        if (aimedPos != null && webRenderPos.contains(aimedPos)) {
            Direction face = getBestFace(aimedPos, webRenderFaces);
            if (face != null) {
                BlockPos support = aimedPos.offset(face.getOpposite());
                aimOnCobweb = mc.world.getBlockState(support).isOf(Blocks.COBWEB);
            }
        }

        ActionType action = ActionType.NONE;

        // 1. Scoop water — highest priority
        if (isWaterAimed && canBucket) {
            action = ActionType.SCOOP_WATER;
        }
        // 2. Burn cobweb with lava — very high priority
        else if (aimOnCobweb && canLava && aimedPos != null) {
            action = ActionType.PLACE_LAVA;
        }
        // 3. Scoop lava
        else if (isLavaAimed && canBucket) {
            action = ActionType.SCOOP_LAVA;
        }
        // 4. Main hand priority — use what you're already holding
        else if (aimedPos != null) {
            if (mainHand == Items.COBWEB && canWeb && webRenderPos.contains(aimedPos)) {
                action = ActionType.PLACE_WEB;
            } else if (mainHand == Items.LAVA_BUCKET && canLava && lavaRenderPos.contains(aimedPos)) {
                action = ActionType.PLACE_LAVA;
            }
            // 5. Fall back to whichever list aimedPos belongs to
            else if (aimedIsWeb && canWeb) {
                action = ActionType.PLACE_WEB;
            } else if (!aimedIsWeb && canLava) {
                action = ActionType.PLACE_LAVA;
            }
            // 6. Cross-fallback: if we aimed a lava pos but only have web, still try
            else if (canWeb && webRenderPos.contains(aimedPos)) {
                action = ActionType.PLACE_WEB;
            } else if (canLava && lavaRenderPos.contains(aimedPos)) {
                action = ActionType.PLACE_LAVA;
            }
        }

        switch (action) {
            case PLACE_WEB   -> placeBlock(aimedPos,  Items.COBWEB,      "蜘蛛网", webRenderFaces);
            case PLACE_LAVA  -> placeBlock(aimedPos,  Items.LAVA_BUCKET, "岩浆",   aimedIsWeb ? webRenderFaces : lavaRenderFaces);
            case SCOOP_WATER -> scoopFluid(aimedFluid, "收水");
            case SCOOP_LAVA  -> scoopFluid(aimedFluid, "收岩浆");
            default          -> { }
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

        // Try every available face until one succeeds
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

        // All faces failed
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

    // ── Validity checks ───────────────────────────────────────────────────────

    private boolean isValidForWeb(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean isValidForLava(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isReplaceable()) return false;
        Fluid fluid = state.getFluidState().getFluid();
        // Don't place lava into water — it would just make stone/obsidian
        return fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER;
    }

    private boolean isWaterSource(BlockPos pos) {
        Fluid f = mc.world.getBlockState(pos).getFluidState().getFluid();
        return f == Fluids.WATER;
    }

    private boolean isLavaSource(BlockPos pos) {
        Fluid f = mc.world.getBlockState(pos).getFluidState().getFluid();
        return f == Fluids.LAVA;
    }

    // ── Prediction & candidate building ──────────────────────────────────────

    private BlockPos getPredictedPos(LivingEntity entity, int ticks) {
        if (ticks <= 0) return entity.getBlockPos();
        double dx = entity.getX() - entity.lastX;
        double dz = entity.getZ() - entity.lastZ;
        return BlockPos.ofFloored(entity.getEntityPos().add(dx * ticks, 0, dz * ticks));
    }

    /**
     * Build web candidates using webPredictTicks and lava candidates using
     * lavaPredictTicks independently — they may or may not overlap.
     */
    private void buildPlaceable(LivingEntity target) {
        BlockPos webBase  = getPredictedPos(target, webPredictTicks.get());
        tryAddWeb(webBase);
        tryAddWeb(webBase.up());
        tryAddWeb(webBase.down());

        BlockPos lavaBase = getPredictedPos(target, lavaPredictTicks.get());
        tryAddLava(lavaBase);
        tryAddLava(lavaBase.up());
        tryAddLava(lavaBase.down());
    }

    private void tryAddWeb(BlockPos pos) {
        if (!isValidForWeb(pos)) return;
        List<Direction> faces = bestFaces(pos);
        if (faces.isEmpty()) return;
        webRenderPos.add(pos);
        webRenderFaces.put(pos, faces);
    }

    private void tryAddLava(BlockPos pos) {
        if (!isValidForLava(pos)) return;
        List<Direction> faces = bestFaces(pos);
        if (faces.isEmpty()) return;
        lavaRenderPos.add(pos);
        lavaRenderFaces.put(pos, faces);
    }

    /**
     * Returns ALL valid support faces for a placement position, sorted by
     * distance from player eye (nearest first).  Multiple faces may be valid
     * and are all stored so:
     *   • The renderer shows every available support face (not just one).
     *   • placeBlock() iterates them in order until one succeeds.
     */
    private List<Direction> bestFaces(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        List<Direction> validFaces = new ArrayList<>();

        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d.getOpposite());
            BlockState supportState = mc.world.getBlockState(support);
            // Support must be solid (non-air, non-replaceable)
            if (supportState.isAir() || supportState.isReplaceable()) continue;
            // Support must be reachable
            if (Vec3d.ofCenter(support).distanceTo(eye) > targetRange.get()) continue;
            validFaces.add(d);
        }

        validFaces.sort(Comparator.comparingDouble(
            d -> Vec3d.ofCenter(pos.offset(d.getOpposite())).squaredDistanceTo(eye)));
        return validFaces;
    }

    private Direction getBestFace(BlockPos pos, Map<BlockPos, List<Direction>> faceMap) {
        List<Direction> faces = faceMap.get(pos);
        if (faces == null || faces.isEmpty()) return null;
        return faces.get(0);
    }

    // ── Raycast helpers ───────────────────────────────────────────────────────

    /**
     * Generic "which block in the given candidate list is the player aiming at?"
     * Uses a solid-block raycast (no fluid penetration) then offsets by hit side.
     */
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
            return candidates.contains(placePos) ? placePos : null;
        }
        return null;
    }

    /** Returns the fluid source block the player is looking at (for scooping). */
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

        // Fluid overlay — ONLY when triggered (fixes the always-on fluid render bug)
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

        // Web candidates — render ALL available support faces per position
        for (BlockPos pos : webRenderPos) {
            List<Direction> faces = webRenderFaces.get(pos);
            if (faces == null || faces.isEmpty()) continue;

            boolean isAimed  = pos.equals(aimedPos) && aimedIsWeb;
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

        // Lava candidates — render ALL available support faces per position
        for (BlockPos pos : lavaRenderPos) {
            List<Direction> faces = lavaRenderFaces.get(pos);
            if (faces == null || faces.isEmpty()) continue;

            boolean isAimed  = pos.equals(aimedPos) && !aimedIsWeb;
            boolean canPlace = hasInHotbar(Items.LAVA_BUCKET) && isValidForLava(pos);
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
            case UP    -> { x1 = bx+inset;   y1 = by+1-T;     z1 = bz+inset;   x2 = bx+1-inset; y2 = by+1+T-inset; z2 = bz+1-inset; }
            case DOWN  -> { x1 = bx+inset;   y1 = by-T+inset; z1 = bz+inset;   x2 = bx+1-inset; y2 = by+T;         z2 = bz+1-inset; }
            case NORTH -> { x1 = bx+inset;   y1 = by+inset;   z1 = bz-T+inset; x2 = bx+1-inset; y2 = by+1-inset;   z2 = bz+T;       }
            case SOUTH -> { x1 = bx+inset;   y1 = by+inset;   z1 = bz+1-T;     x2 = bx+1-inset; y2 = by+1-inset;   z2 = bz+1+T-inset; }
            case WEST  -> { x1 = bx-T+inset; y1 = by+inset;   z1 = bz+inset;   x2 = bx+T;       y2 = by+1-inset;   z2 = bz+1-inset; }
            case EAST  -> { x1 = bx+1-T;     y1 = by+inset;   z1 = bz+inset;   x2 = bx+1+T-inset; y2 = by+1-inset; z2 = bz+1-inset; }
            default    -> { return; }
        }
        event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, ShapeMode.Both, 0);
    }
}