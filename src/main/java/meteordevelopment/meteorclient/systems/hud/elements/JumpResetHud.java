package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.JumpReset;
import meteordevelopment.meteorclient.systems.modules.movement.UltimateSprint;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class JumpResetHud extends HudElement {
    public static final HudElementInfo<JumpResetHud> INFO = new HudElementInfo<>(
            Hud.GROUP,
            "jump-reset",
            "Notifies you when you perform a jump reset.",
            JumpResetHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Integer> displayDuration = sgGeneral.add(new IntSetting.Builder()
            .name("display-duration").description("Display duration in ticks.").defaultValue(40).min(0).sliderMax(100).build());

    private final Setting<SettingColor> perfectColor = sgColors.add(new ColorSetting.Builder()
            .name("perfect-color").description("Color for perfect jump reset.").defaultValue(new SettingColor(85, 255, 85)).build());

    private final Setting<SettingColor> defaultColor = sgColors.add(new ColorSetting.Builder()
            .name("default-color").description("Default color.").defaultValue(new SettingColor(170, 170, 170)).build());

    private final Setting<SettingColor> backgroundColor = sgColors.add(new ColorSetting.Builder()
            .name("background-color").description("Background color.").defaultValue(new SettingColor(25, 25, 25, 170)).build());

    private String displayText = "Waiting...";
    private SettingColor displayColor;
    private float alpha = 1.0f;
    private int displayTicks = 0;

    private JumpResetHud() {
        super(INFO);
        displayColor = defaultColor.get();
    }

    @Override
    public void tick(HudRenderer renderer) {
        updateDisplayState();
        calculateSize(renderer);
    }

    private void calculateSize(HudRenderer renderer) {
        double width = renderer.textWidth(displayText, true);
        double height = renderer.textHeight(true) + 8;
        setSize(width + 12, height + 5);
    }

    private void updateDisplayState() {
        JumpReset jumpReset = Modules.get().get(JumpReset.class);
        UltimateSprint ultimateSprint = Modules.get().get(UltimateSprint.class);
        
        if (jumpReset == null || !jumpReset.isActive()) {
            displayText = "Waiting...";
            displayColor = defaultColor.get();
            alpha = 1.0f;
            return;
        }

        // 显示 skipSprintSetting 状态和疾跑状态
        String statusText = "";
        if (ultimateSprint != null && mc.player != null) {
            String skipSprintStatus = UltimateSprint.skipSprintSetting ? "[Active] " : "[Inactive] ";
            String sprintStatus = mc.player.isSprinting() ? "[Sprint] " : "[Walk] ";
            statusText = skipSprintStatus + sprintStatus;
        }

        int hurt = jumpReset.hurtTime;
        int jump = jumpReset.jumpTime;

        if (hurt == 0 || jump == 0) {
            // 显示是否可以进行 JumpReset（被打了）
            boolean canJumpReset = hurt > 0;
            displayText = statusText + (canJumpReset ? "Ready!" : "Waiting...") + " [" + jumpReset.triggerCount + "]";
            displayColor = defaultColor.get();
            alpha = 1.0f;
            return;
        }

        int dif = jump - hurt;
        if (dif > 0 && displayTicks < displayDuration.get()) {
            if (displayTicks == 0) {
                displayTicks = 1;
            } else {
                displayTicks++;
            }

            if (Math.abs(dif) <= jumpReset.perfectThreshold.get()) {
                displayText = statusText + "Perfect: " + dif + "t [" + jumpReset.triggerCount + "]";
                displayColor = perfectColor.get();
            } else {
                displayText = statusText + "Dif: " + dif + "t [" + jumpReset.triggerCount + "]";
                displayColor = defaultColor.get();
            }
            alpha = 1.0f;
        } else if (displayTicks > 0 && displayTicks < displayDuration.get()) {
            displayTicks++;
            alpha = Math.max(0.0f, 1.0f - displayTicks / 20.0f);
        } else {
            // 显示是否可以进行 JumpReset
            boolean canJumpReset = hurt > 0;
            displayText = statusText + (canJumpReset ? "Ready!" : "Waiting...") + " [" + jumpReset.triggerCount + "]";
            displayColor = defaultColor.get();
            alpha = 1.0f;
            displayTicks = 0;
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (renderer == null) return;
        
        if (alpha <= 0.01f && !isInEditor()) {
            return;
        }

        if (alpha <= 0.01f) {
            this.setSize(80, 24);
            return;
        }

        double x = this.x;
        double y = this.y;
        double width = getWidth();
        double height = getHeight();

        if (backgroundColor == null || displayColor == null || displayText == null) return;

        try {
            Color bgColor = new Color(backgroundColor.get());
            bgColor.a = (int) (alpha * bgColor.a);
            renderer.quad(x, y, width, height, bgColor);

            Color barColor = new Color(displayColor);
            barColor.a = (int) (alpha * 255);
            renderer.quad(x, y, width, y + 2, barColor);

            Color textColor = new Color(255, 255, 255);
            textColor.a = (int) (alpha * 255);

            double textX = x + (width - renderer.textWidth(displayText, true)) / 2;
            double textY = y + (height - renderer.textHeight(true)) / 2 + 1;

            renderer.text(displayText, textX, textY, textColor, true);
        } catch (Exception e) {
            // 捕获异常，防止崩溃
        }
    }
}