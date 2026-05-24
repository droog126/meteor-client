package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.KeyBindingAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.UltimateSprint;
import meteordevelopment.orbit.EventHandler;

public class JumpReset extends Module {
    public static JumpReset INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> requireSkipSprint = sgGeneral.add(new BoolSetting.Builder()
            .name("require-skip-sprint").description("Only trigger when skipSprintSetting is active (from TriggerBotV2)").defaultValue(false).build());

    public final Setting<Integer> perfectThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("perfect-threshold").description("Perfect jump reset threshold in ticks").defaultValue(2).min(0).sliderMax(20).build());

    public int hurtTime = 0;
    public int jumpTime = 0;
    public int triggerCount = 0;

    public JumpReset() {
        super(Categories.Combat, "jump-reset", "Notifies you when you perform a jump reset.");
        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        hurtTime = 0;
        jumpTime = 0;
        triggerCount = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 检查是否需要 skipSprintSetting
        if (requireSkipSprint.get()) {
            UltimateSprint ultimateSprint = Modules.get().get(UltimateSprint.class);
            if (!ultimateSprint.isActive() || !UltimateSprint.skipSprintSetting) return;
        }

        // 检测受伤
        if (mc.player.hurtTime > 0 && hurtTime == 0) {
            hurtTime = 1;
            // 被打了就强制起跳（使用按键模拟）
            if (mc.player.isOnGround()) {
                KeyBindingAccessor accessor = (KeyBindingAccessor) mc.options.jumpKey;
                accessor.meteor$setTimesPressed(accessor.meteor$getTimesPressed() + 1);
                triggerCount++;  // 增加计数器
            }
        } else if (hurtTime > 0) {
            hurtTime++;
        }

        // 检测跳跃
        boolean onGround = mc.player.isOnGround();
        
        if (!onGround && jumpTime == 0 && hurtTime > 0) {
            jumpTime = 1;
        } else if (jumpTime > 0) {
            jumpTime++;
        }

        // 落地重置
        if (onGround) {
            hurtTime = 0;
            jumpTime = 0;
        }
    }
}