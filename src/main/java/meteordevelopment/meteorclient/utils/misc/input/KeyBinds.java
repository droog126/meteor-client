/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.input;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KeyBinds {
    public static final int OPEN_GUI_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;
    public static final int OPEN_COMMANDS_KEY = GLFW.GLFW_KEY_PERIOD;

    private KeyBinds() {
    }

    public static KeyBinding[] apply(KeyBinding[] binds) {
        // 不添加任何按键绑定到设置中
        return binds;
    }
}
