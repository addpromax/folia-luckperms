/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.folia;

import me.lucko.luckperms.bukkit.BukkitCommandExecutor;
import me.lucko.luckperms.bukkit.LPBukkitPlugin;
import me.lucko.luckperms.bukkit.inject.permissible.LuckPermsPermissible;
import me.lucko.luckperms.bukkit.inject.permissible.PermissibleInjector;
import me.lucko.luckperms.bukkit.inject.permissible.PermissibleMonitoringInjector;
import me.lucko.luckperms.bukkit.inject.server.InjectorDefaultsMap;
import me.lucko.luckperms.bukkit.inject.server.InjectorPermissionMap;
import me.lucko.luckperms.bukkit.inject.server.InjectorSubscriptionMap;
import me.lucko.luckperms.bukkit.listeners.BukkitAutoOpListener;
import me.lucko.luckperms.bukkit.listeners.BukkitCommandListUpdater;
import me.lucko.luckperms.bukkit.util.PluginManagerUtil;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.model.User;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * LuckPerms implementation for the Folia API.
 */
public class LPFoliaPlugin extends LPBukkitPlugin {

    public LPFoliaPlugin(LPFoliaBootstrap bootstrap) {
        super(bootstrap);
    }

    @Override
    public LPFoliaBootstrap getBootstrap() {
        return (LPFoliaBootstrap) this.bootstrap;
    }

    @Override
    protected void registerCommands() {
        PluginCommand command = this.bootstrap.getLoader().getCommand("luckperms");
        if (command == null) {
            getLogger().severe("Unable to register /luckperms command with the server");
            return;
        }

        // Folia doesn't have AsyncTabCompleteEvent in the same way, but it should be fine.
        this.commandManager = new BukkitCommandExecutor(this, command);
        this.commandManager.register();
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new FoliaConnectionListener(this);
        this.bootstrap.getServer().getPluginManager().registerEvents(this.connectionListener, this.bootstrap.getLoader());
        this.bootstrap.getServer().getPluginManager().registerEvents(new me.lucko.luckperms.bukkit.listeners.BukkitPlatformListener(this), this.bootstrap.getLoader());
    }

    @Override
    protected void setupPlatformHooks() {
        // inject our own custom permission maps
        Runnable[] injectors = new Runnable[]{
                new InjectorSubscriptionMap(this)::inject,
                new InjectorPermissionMap(this)::inject,
                new InjectorDefaultsMap(this)::inject,
                new PermissibleMonitoringInjector(this, PermissibleMonitoringInjector.Mode.INJECT)
        };

        for (Runnable injector : injectors) {
            injector.run();

            // schedule another injection after all plugins have loaded
            // In Folia, we use GlobalRegionScheduler.
            this.bootstrap.getServer().getGlobalRegionScheduler().runDelayed(this.bootstrap.getLoader(), task -> injector.run(), 1);
        }

        PluginManagerUtil.injectDependency(this.bootstrap.getServer().getPluginManager(), this.bootstrap.getLoader().getName(), "Vault");

        // Provide vault support
        tryVaultHook(false);
    }

    @Override
    protected void performFinalSetup() {
        // register permissions
        PluginManager pluginManager = this.bootstrap.getServer().getPluginManager();
        PermissionDefault permDefault = getConfiguration().get(ConfigKeys.COMMANDS_ALLOW_OP) ? PermissionDefault.OP : PermissionDefault.FALSE;

        for (CommandPermission permission : CommandPermission.values()) {
            Permission bukkitPermission = new Permission(permission.getPermission(), permDefault);
            pluginManager.removePermission(bukkitPermission);
            pluginManager.addPermission(bukkitPermission);
        }

        // remove all operators on startup if they're disabled
        if (!getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            // Use Folia's async scheduler instead of Bukkit's
            this.bootstrap.getScheduler().executeAsync(() -> {
                for (OfflinePlayer player : this.bootstrap.getServer().getOperators()) {
                    player.setOp(false);
                }
            });
        }

        // register autoop listener
        if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
            getApiProvider().getEventBus().subscribe(new BukkitAutoOpListener(this));
        }

        // register bukkit command list updater
        if (getConfiguration().get(ConfigKeys.UPDATE_CLIENT_COMMAND_LIST) && BukkitCommandListUpdater.isSupported()) {
            getApiProvider().getEventBus().subscribe(new BukkitCommandListUpdater(this));
        }

        // Load any online users (in the case of a reload)
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            this.bootstrap.getScheduler().executeAsync(() -> {
                try {
                    User user = this.connectionListener.loadUser(player.getUniqueId(), player.getName());
                    if (user != null) {
                        this.bootstrap.getScheduler().executeSync(() -> {
                            try {
                                LuckPermsPermissible lpPermissible = new LuckPermsPermissible(player, user, this);
                                PermissibleInjector.inject(player, lpPermissible, getLogger());
                            } catch (Throwable t) {
                                getLogger().severe("Exception thrown when setting up permissions for " +
                                        player.getUniqueId() + " - " + player.getName(), t);
                            }
                        });
                    }
                } catch (Exception e) {
                    getLogger().severe("Exception occurred whilst loading data for " +
                            player.getUniqueId() + " - " + player.getName(), e);
                }
            });
        }
    }

}
