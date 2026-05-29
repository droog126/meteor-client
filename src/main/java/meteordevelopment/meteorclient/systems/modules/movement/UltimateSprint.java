package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.KeyBindingAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.TriggerBotV2;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;

import org.lwjgl.glfw.GLFW;

public class UltimateSprint extends Module {
    public static UltimateSprint instance = null;

    private final SettingGroup sgPredict = settings.createGroup("预判暴击 (Predict Crit)");

    private final Setting<Boolean> autoUnsprintForCrit = sgPredict.add(new BoolSetting.Builder()
            .name("auto-unsprint-for-crit")
            .description("跳跃顶点后x tick内瞄到人自动松开W持续y tick")
            .defaultValue(true)
            .build());

    private final Setting<Integer> predictWindowTicks = sgPredict.add(new IntSetting.Builder()
            .name("predict-window-ticks")
            .description("顶点后的触发窗口tick数（x）")
            .defaultValue(3)
            .min(1)
            .max(10)
            .visible(autoUnsprintForCrit::get)
            .build());

    private final Setting<Integer> unsprintDurationTicks = sgPredict.add(new IntSetting.Builder()
            .name("unsprint-duration-ticks")
            .description("自动松开W的持续tick数（y）")
            .defaultValue(2)
            .min(1)
            .max(10)
            .visible(autoUnsprintForCrit::get)
            .build());

    public UltimateSprint() {
        super(Categories.Movement, "ultimate-sprint", "Smart W-Tap: Handles attack callbacks with configurable delay.");
        instance = this;
    }

    private Runnable externalCallback = null;
    private int currentDelayTicks = 0;

    // ========== 自动预取消疾跑状态 ==========
    private boolean autoUnsprintActive = false;
    private int autoUnsprintTickCounter = 0;

    // ========== 命中后跳过疾跑设置状态 ==========
    public static boolean skipSprintSetting = false;
    private boolean wWasReleased = false;

    // ========== 跳跃顶点追踪 ==========
    private boolean wasOnGround = true;
    private boolean inAir = false;
    private boolean passedApex = false;
    private int postApexTickCounter = 0;

    @Override
    public void onActivate() {
        instance = this;
        resetRequests();
        resetAutoUnsprint();
        resetApexTracking();
        resetSkipSprintSetting();
    }

    @Override
    public void onDeactivate() {
        resetRequests();
        resetAutoUnsprint();
        resetApexTracking();
        resetSkipSprintSetting();
        instance = null;
    }

    private void resetSkipSprintSetting() {
        skipSprintSetting = false;
        wWasReleased = false;
    }

    public static void setSkipSprintSetting() {
        if (instance != null) {
            instance.skipSprintSetting = true;
            instance.wWasReleased = false;
        }
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



    private void resetAutoUnsprint() {
        autoUnsprintActive = false;
        autoUnsprintTickCounter = 0;
    }

    private void resetApexTracking() {
        wasOnGround = true;
        inAir = false;
        passedApex = false;
        postApexTickCounter = 0;
    }

    public static void requestCritUnsprint(Runnable callback) {
        if (instance != null) {
            boolean isPressW = Input.isKeyPressed(GLFW.GLFW_KEY_W);
            boolean isSprinting = instance.mc.player != null && instance.mc.player.isSprinting();
            System.out.println(isPressW + " " + isSprinting + " " + skipSprintSetting);
            if(skipSprintSetting || !isSprinting){
                callback.run();
                return;
            }
            instance.externalCallback = callback;
            instance.currentDelayTicks = 1;
        }
    }

    public static void clearCritUnsprintRequest() {
        if (instance != null) {
            instance.resetRequests();
        }
    }





    public Entity getTarget() {
        return TriggerBotV2.currentTarget;
    }

    @EventHandler(priority = EventPriority.MEDIUM)
    private void onTick(TickEvent.Pre event) {
        if (!isActive() || mc.player == null || mc.world == null)
            return;

        // 如果FreeCam激活，直接返回
        Freecam freecam = Modules.get().get(Freecam.class);
        if (freecam != null && freecam.isActive()) {
            return;
        }


        boolean isPressR = Input.isKeyPressed(GLFW.GLFW_KEY_R);
        if (isPressR || mc.currentScreen != null) {
            stopSprinting();
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            resetAutoUnsprint();
            resetRequests();
            return;
        }

        boolean isPressW = Input.isKeyPressed(GLFW.GLFW_KEY_W);

        if (externalCallback != null) {
            if (currentDelayTicks <= 0) {
                resetRequests();
            } else {
                currentDelayTicks--;
                mc.options.forwardKey.setPressed(false);
                return;
            }
        }

        // ========== 处理跳过疾跑设置状态 ==========
        if (skipSprintSetting) {
            if (!isPressW) {
                // W松开了，标记已松开
                wWasReleased = true;
            } else if (wWasReleased) {
                // W松开后再按下，恢复正常
                resetSkipSprintSetting();
            }
        }

        // ========== 追踪跳跃顶点 ==========
        boolean onGround = mc.player.isOnGround();
        double velocityY = mc.player.getVelocity().y;

        if (!onGround && wasOnGround) {
            inAir = true;
            passedApex = false;
            postApexTickCounter = 0;
        } else if (inAir && !passedApex) {
            // 空中，尚未过顶点
            if (velocityY < 0) {
                // 速度变负，刚经过顶点
                passedApex = true;
                postApexTickCounter = 0;
            }
        } else if (inAir && passedApex) {
            // 已过顶点，计数
            postApexTickCounter++;
        }

        if (onGround) {
            // 落地重置
            inAir = false;
            passedApex = false;
            postApexTickCounter = 0;
        }
        wasOnGround = onGround;

        // ========== 自动预取消疾跑逻辑 ==========
        if (autoUnsprintForCrit.get()) {
            handleAutoUnsprintForCrit();
        }

        // ========== Debug 信息输出 ==========
        // if (inAir) { // 只在空中时输出，避免刷屏
        //     System.out.println("[UltimateSprint Debug] inAir=" + inAir + 
        //                      ", passedApex=" + passedApex + 
        //                      ", postApexTick=" + postApexTickCounter + "/" + predictWindowTicks.get() +
        //                      ", target=" + (getTarget() != null ? getTarget().getName().getString() : "null") +
        //                      ", requireTriggerBot=" + requireTriggerBot.get() +
        //                      ", triggerBotActive=" + isTriggerBotV2Active() +
        //                      ", autoUnsprintActive=" + autoUnsprintActive);
        // }

        // 如果处于自动预取消状态，强制停止疾跑并松开W
        if (autoUnsprintActive) {
            stopSprinting();
            autoUnsprintTickCounter++;

            if (autoUnsprintTickCounter >= unsprintDurationTicks.get()) {
                resetAutoUnsprint();
            }
        }


        if (!autoUnsprintActive) {
            if (isPressW) {
                startSprinting();
            } else {
                stopSprinting();
            }
        }
    }

    /**
     * 处理自动预取消疾跑
     * 条件：顶点后 x tick 窗口内 + 瞄到实体 + TriggerBotV2 激活
     */
    private void handleAutoUnsprintForCrit() {
        if (autoUnsprintActive) {
            return;
        }
        
        if (!inAir || !passedApex) {
            return;
        }

        // 必须在顶点后 x tick 窗口内
        if (postApexTickCounter > predictWindowTicks.get()) {
            return;
        }

        // 必须瞄到有效实体
        Entity target = getTarget();
        if (target == null) {
            return;
        }

        // 只有 TriggerBotV2 激活时才触发
        if (!isTriggerBotV2Active()) {
            return;
        }

        // 触发自动松开W
        // System.out.println("[UltimateSprint] TRIGGERED auto unsprint!");
        autoUnsprintActive = true;
        autoUnsprintTickCounter = 0;
    }

    /** 检查 TriggerBotV2 是否激活 */
    private boolean isTriggerBotV2Active() {
        Module triggerBot = Modules.get().get(TriggerBotV2.class);
        return triggerBot != null && triggerBot.isActive();
    }

    private void stopSprinting() {
        mc.options.forwardKey.setPressed(false);
    }

    private void startSprinting() {
        mc.options.forwardKey.setPressed(true);
        if (skipSprintSetting) {
            return;
        }
        if (!mc.player.isSprinting() && mc.player.isOnGround()) {
            KeyBindingAccessor accessor = (KeyBindingAccessor) mc.options.sprintKey;
            accessor.meteor$setTimesPressed(accessor.meteor$getTimesPressed() + 1);
        }
    }





}