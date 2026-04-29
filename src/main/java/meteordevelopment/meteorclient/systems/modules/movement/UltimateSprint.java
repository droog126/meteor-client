package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.entity.Entity;

import org.lwjgl.glfw.GLFW;

public class UltimateSprint extends Module {
    public static UltimateSprint instance;

    public UltimateSprint() {
        super(Categories.Movement, "ultimate-sprint", "Smart W-Tap: Handles attack callbacks with configurable delay.");
    }

    // ========== 原有 Crit Unsprint 状态 ==========
    private Runnable externalCallback = null;
    private int currentDelayTicks = 0;

    // ========== 新增：W-Tap 状态 ==========
    private Runnable wtapCallback = null;
    private int wtapTicks = 0;
    private boolean wtapActive = false;
    // =======================================

    @Override
    public void onActivate() {
        instance = this;
        resetRequests();
        resetWtap();
    }

    @Override
    public void onDeactivate() {
        resetRequests();
        resetWtap();
        instance = null;
    }

    private void resetRequests() {
        if (externalCallback != null) {
            if (getTarget() != null) {
                externalCallback.run();
            }
        }
        externalCallback = null;
        currentDelayTicks = 0;
    }

    // ========== 新增：W-Tap 重置 ==========
    private void resetWtap() {
        if (wtapCallback != null) {
            wtapCallback.run();
        }
        wtapCallback = null;
        wtapTicks = 0;
        wtapActive = false;
    }
    // =======================================

    public static void requestCritUnsprint(Runnable callback) {
        if (instance != null) {
            instance.externalCallback = callback;
            boolean isPressW = Input.isKeyPressed(GLFW.GLFW_KEY_W);

            instance.currentDelayTicks = isPressW ? 1 : 0;
        }
    }

    public static void clearCritUnsprintRequest() {
        if (instance != null) {
            instance.resetRequests();
        }
    }

    public static void requestWSprint() {
        if (instance != null && !instance.wtapActive) {
            instance.wtapActive = true;
            instance.wtapTicks = 0;
        }
    }

    public static void clearWSprintRequest() {
        if (instance != null) {
            instance.resetWtap();
        }
    }

    public Entity getTarget() {
        if (mc.crosshairTarget instanceof EntityHitResult ehr) {
            Entity e = ehr.getEntity();
            if (e == null || !e.isAlive()) {
                return null;
            } else {
                return e;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MEDIUM)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;
        boolean isPressW = Input.isKeyPressed(GLFW.GLFW_KEY_W);
        if (wtapActive) {
            wtapTicks++;
            if (wtapTicks > 1) {
                wtapActive = false;
                wtapTicks = 0;
            } else {
                stopSprinting();
                mc.options.backKey.setPressed(true);
                return;
            }
        }

        if (externalCallback != null) {
            if (currentDelayTicks <= 0) {
                resetRequests();
            } else {
                currentDelayTicks--;
                stopSprinting();
                return;
            }
        }

        if (externalCallback == null && !wtapActive) {
            if (isPressW) {
                startSprinting();
            }else{
                stopSprinting();
            }
            mc.options.backKey.setPressed(Input.isKeyPressed(GLFW.GLFW_KEY_S));
            mc.options.leftKey.setPressed(Input.isKeyPressed(GLFW.GLFW_KEY_A));
            mc.options.rightKey.setPressed(Input.isKeyPressed(GLFW.GLFW_KEY_D));
        }
    }

    private void stopSprinting() {
        mc.options.sprintKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
    }

    private void startSprinting() {
        mc.options.sprintKey.setPressed(true);
        mc.options.forwardKey.setPressed(true);
    }

    public boolean rageSprint() {
        return false;
    }

    public boolean unsprintInWater() {
        return false;
    }
}