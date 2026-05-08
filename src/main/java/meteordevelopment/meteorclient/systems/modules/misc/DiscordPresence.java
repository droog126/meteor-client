/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

public class DiscordPresence extends Module {
    public DiscordPresence() {
        super(Categories.Misc, "discord-presence", "Displays Meteor as your presence on Discord.");

        runInMainMenu = true;
        showInModuleList = false;
    }

    @Override
    public void onActivate() {
        // 禁用 Discord Presence 功能
    }

    @Override
    public void onDeactivate() {
        // 禁用 Discord Presence 功能
    }
}
