package net.frankheijden.serverutils.bukkit.commands;

import static net.frankheijden.serverutils.common.config.Messenger.sendMessage;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.RegisteredCommand;
import co.aikar.commands.RootCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Dependency;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Subcommand;
import dev.frankheijden.minecraftreflection.MinecraftReflectionVersion;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.frankheijden.serverutils.bukkit.ServerUtils;
import net.frankheijden.serverutils.bukkit.entities.BukkitLoadResult;
import net.frankheijden.serverutils.bukkit.managers.BukkitPluginManager;
import net.frankheijden.serverutils.bukkit.reflection.RCraftServer;
import net.frankheijden.serverutils.bukkit.reflection.RDedicatedServer;
import net.frankheijden.serverutils.bukkit.utils.BukkitUtils;
import net.frankheijden.serverutils.bukkit.utils.ReloadHandler;
import net.frankheijden.serverutils.common.config.Messenger;
import net.frankheijden.serverutils.common.entities.AbstractResult;
import net.frankheijden.serverutils.common.entities.CloseableResult;
import net.frankheijden.serverutils.common.entities.Result;
import net.frankheijden.serverutils.common.entities.ServerCommandSender;
import net.frankheijden.serverutils.common.utils.FormatBuilder;
import net.frankheijden.serverutils.common.utils.ForwardFilter;
import net.frankheijden.serverutils.common.utils.HexUtils;
import net.frankheijden.serverutils.common.utils.ListBuilder;
import net.frankheijden.serverutils.common.utils.ListFormat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

@CommandAlias("su|serverutils")
public class CommandServerUtils extends BaseCommand {

    private static final Map<String, ReloadHandler> supportedConfigs;

    static {
        supportedConfigs = new HashMap<>();
        supportedConfigs.put("bukkit", RCraftServer::reloadBukkitConfiguration);
        supportedConfigs.put("commands.yml", RCraftServer::reloadCommandsConfiguration);
        supportedConfigs.put("server-icon.png", RCraftServer::loadIcon);
        supportedConfigs.put("banned-ips.json", RCraftServer::reloadIpBans);
        supportedConfigs.put("banned-players.json", RCraftServer::reloadProfileBans);
        supportedConfigs.put("server.properties", RDedicatedServer::reloadServerProperties);
    }

    @Dependency
    private ServerUtils plugin;

    public static Set<String> getSupportedConfigs() {
        return supportedConfigs.keySet();
    }

    /**
     * Shows the help page to the sender.
     * @param commandSender The sender of the command.
     */
    @Default
    @Subcommand("help")
    @CommandPermission("serverutils.help")
    @Description("Afficher la page d'aide de JLS Plugin même effet que la commande /su.")
    public void onHelp(CommandSender commandSender) {
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);
        Messenger.sendMessage(sender, "serverutils.help.header");

        FormatBuilder builder = FormatBuilder.create(Messenger.getMessage("serverutils.help.format"))
                .orderedKeys("%command%", "%subcommand%", "%help%");

        Set<String> rootCommands = new HashSet<>();
        for (RootCommand root : plugin.getCommandManager().getRegisteredRootCommands()) {
            String rootName = root.getDefCommand().getName();
            if (!rootCommands.add(rootName)) continue;
            builder.add(rootName, "", root.getDescription());

            Set<String> subCommands = new HashSet<>();
            for (RegisteredCommand<?> sub : root.getSubCommands().values()) {
                String name = sub.getPrefSubCommand().toLowerCase();
                if (name.isEmpty()) continue;
                if (!subCommands.add(name)) continue;
                builder.add(rootName, " " + name, sub.getHelpText());
            }
        }
        builder.sendTo(sender);
        Messenger.sendMessage(sender, "serverutils.help.footer");
    }

    /**
     * Reloads the configurations of ServerUtils.
     * @param sender The sender of the command.
     */
    @Subcommand("reload|r")
    @CommandPermission("serverutils.reload")
    @Description("Recharger les configuration de JLS Plugin.")
    public void onReload(CommandSender sender) {
        plugin.reload();
        sendMessage(BukkitUtils.wrap(sender), "serverutils.success",
                "%action%", "reload",
                "%what%", "ServerUtils configurations");
    }

    /**
     * Reloads a config from a set of configurations of the server.
     * @param commandSender The sender of the command.
     * @param config The configuration to reload.
     */
    @Subcommand("reloadconfig|rc")
    @CommandCompletion("@supportedConfigs")
    @CommandPermission("serverutils.reloadconfig")
    @Description("Recharger les configuration d'un plugin.")
    public void onReloadCommands(CommandSender commandSender, String config) {
        ReloadHandler handler = supportedConfigs.get(config);
        if (handler == null) {
            this.doHelp(commandSender);
            return;
        }
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);

        String[] replacements = new String[]{ "%action%", "reload", "%what%", config };

        ForwardFilter filter = new ForwardFilter(sender);
        filter.start(Bukkit.getLogger());
        try {
            handler.handle();
            filter.stop(Bukkit.getLogger());

            String path = "serverutils." + (filter.hasWarnings() ? "warning" : "success");
            sendMessage(sender, path, replacements);
        } catch (Exception ex) {
            filter.stop(Bukkit.getLogger());

            ex.printStackTrace();
            sendMessage(sender, "serverutils.error", replacements);
        }
    }

    /**
     * Loads the specified plugin on the server.
     * @param commandSender The sender of the command.
     * @param jarFile The filename of the plugin in the plugins/ directory.
     */
    @Subcommand("loadplugin|lp")
    @CommandCompletion("@pluginJars")
    @CommandPermission("serverutils.loadplugin")
    @Description("Charger le fichier .jar spécifié comme un plugin.")
    public void onLoadPlugin(CommandSender commandSender, String jarFile) {
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);

        BukkitLoadResult loadResult = BukkitPluginManager.get().loadPlugin(jarFile);
        if (!loadResult.isSuccess()) {
            loadResult.getResult().sendTo(sender, "load", jarFile);
            return;
        }

        Result result = BukkitPluginManager.get().enablePlugin(loadResult.get());
        result.sendTo(sender, "load", jarFile);
    }

    /**
     * Unloads the specified plugin from the server.
     * @param commandSender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("unloadplugin|up")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.unloadplugin")
    @Description("Désactivé et décharger le plugin spécifié.")
    public void onUnloadPlugin(CommandSender commandSender, String pluginName) {
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);

        Result disableResult = BukkitPluginManager.get().disablePlugin(pluginName);
        if (disableResult != Result.SUCCESS && disableResult != Result.ALREADY_DISABLED) {
            disableResult.sendTo(sender, "disabl", pluginName);
            return;
        }

        CloseableResult result = BukkitPluginManager.get().unloadPlugin(pluginName);
        result.getResult().sendTo(sender, "unload", pluginName);
        result.tryClose();
    }

    /**
     * Reloads the specified plugin on the server.
     * @param sender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("reloadplugin|rp")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.reloadplugin")
    @Description("Recharger le plugin spécifié.")
    public void onReloadPlugin(CommandSender sender, String pluginName) {
        // Wacky method to have the resources needed for the reload in memory, in case of a self reload.
        HexUtils utils = new HexUtils();
        Map<String, Object> section = Messenger.getInstance().getConfig().getMap("serverutils");
        String result = BukkitPluginManager.get().reloadPlugin(pluginName).toString();

        String msg = (String) section.get(result.toLowerCase());
        if (msg != null && !msg.isEmpty()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', utils.convertHexString(
                    msg.replace("%action%", "reload").replace("%what%", pluginName))));
        }
    }

    /**
     * Enables the specified plugin on the server.
     * @param sender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("enableplugin|ep")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.enableplugin")
    @Description("Activer le plugin chargé.")
    public void onEnablePlugin(CommandSender sender, String pluginName) {
        Result result = BukkitPluginManager.get().enablePlugin(pluginName);
        result.sendTo(BukkitUtils.wrap(sender), "enabl", pluginName);
    }

    /**
     * Disables the specified plugin on the server.
     * @param sender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("disableplugin|dp")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.disableplugin")
    @Description("Désactiver le plugin spécifié dans la commandes.")
    public void onDisablePlugin(CommandSender sender, String pluginName) {
        Result result = BukkitPluginManager.get().disablePlugin(pluginName);
        result.sendTo(BukkitUtils.wrap(sender), "disabl", pluginName);
    }

    /**
     * Watches the given plugin and reloads it when a change is detected to the file.
     * @param sender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("watchplugin|wp")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.watchplugin")
    @Description("Surveiller les changements pour les plugins spécifiés.")
    public void onWatchPlugin(CommandSender sender, String pluginName) {
        ServerCommandSender commandSender = BukkitUtils.wrap(sender);
        AbstractResult result = BukkitPluginManager.get().watchPlugin(commandSender, pluginName);
        result.sendTo(commandSender, "watch", pluginName);
    }

    /**
     * Stops watching the given plugin.
     * @param sender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("unwatchplugin|uwp")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.watchplugin")
    @Description("Arrêter la surveillance des changement pour les plugin spécifié.")
    public void onUnwatchPlugin(CommandSender sender, String pluginName) {
        AbstractResult result = BukkitPluginManager.get().unwatchPlugin(pluginName);
        result.sendTo(BukkitUtils.wrap(sender), "unwatch", pluginName);
    }

    /**
     * Shows information about the specified plugin.
     * @param commandSender The sender of the command.
     * @param pluginName The plugin name.
     */
    @Subcommand("plugininfo|pi")
    @CommandCompletion("@plugins")
    @CommandPermission("serverutils.plugininfo")
    @Description("Afficher les informations du plugin demandé.")
    public void onPluginInfo(CommandSender commandSender, String pluginName) {
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);

        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            Result.NOT_EXISTS.sendTo(sender, "fetch", pluginName);
            return;
        }

        PluginDescriptionFile description = plugin.getDescription();
        String format = Messenger.getMessage("serverutils.plugininfo.format");
        String listFormatString = Messenger.getMessage("serverutils.plugininfo.list_format");
        String seperator = Messenger.getMessage("serverutils.plugininfo.seperator");
        String lastSeperator = Messenger.getMessage("serverutils.plugininfo.last_seperator");

        ListFormat<String> listFormat = str -> listFormatString.replace("%value%", str);

        Messenger.sendMessage(sender, "serverutils.plugininfo.header");

        FormatBuilder builder = FormatBuilder.create(format)
                .orderedKeys("%key%", "%value%")
                .add("Name", plugin.getName())
                .add("Full Name", description.getFullName())
                .add("Version", description.getVersion());
        if (MinecraftReflectionVersion.MINOR >= 13) builder.add("API Version", description.getAPIVersion());
        builder.add("Website", description.getWebsite())
                .add("Authors", ListBuilder.create(description.getAuthors())
                        .format(listFormat)
                        .seperator(seperator)
                        .lastSeperator(lastSeperator)
                        .toString())
                .add("Description", description.getDescription())
                .add("Main", description.getMain())
                .add("Prefix", description.getPrefix())
                .add("Load Order", description.getLoad().name())
                .add("Load Before", ListBuilder.create(description.getLoadBefore())
                        .format(listFormat)
                        .seperator(seperator)
                        .lastSeperator(lastSeperator)
                        .toString())
                .add("Depend", ListBuilder.create(description.getDepend())
                        .format(listFormat)
                        .seperator(seperator)
                        .lastSeperator(lastSeperator)
                        .toString())
                .add("Soft Depend", ListBuilder.create(description.getSoftDepend())
                        .format(listFormat)
                        .seperator(seperator)
                        .lastSeperator(lastSeperator)
                        .toString());
        if (MinecraftReflectionVersion.MINOR >= 15) {
            builder.add("Provides", ListBuilder.create(description.getProvides())
                    .format(listFormat)
                    .seperator(seperator)
                    .lastSeperator(lastSeperator)
                    .toString());
        }

        builder.sendTo(sender);
        Messenger.sendMessage(sender, "serverutils.plugininfo.footer");
    }

    /**
     * Shows information about a provided command.
     * @param commandSender The sender of the command.
     * @param command The command to lookup.
     */
    @Subcommand("commandinfo|ci")
    @CommandCompletion("@commands")
    @CommandPermission("serverutils.commandinfo")
    @Description("Afficher les informations de la commande demandée.")
    public void onCommandInfo(CommandSender commandSender, String command) {
        ServerCommandSender sender = BukkitUtils.wrap(commandSender);

        Command cmd = BukkitPluginManager.getCommand(command);
        if (cmd == null) {
            Messenger.sendMessage(sender, "serverutils.commandinfo.not_exists");
            return;
        }

        String format = Messenger.getMessage("serverutils.commandinfo.format");
        String listFormatString = Messenger.getMessage("serverutils.commandinfo.list_format");
        String seperator = Messenger.getMessage("serverutils.commandinfo.seperator");
        String lastSeperator = Messenger.getMessage("serverutils.commandinfo.last_seperator");

        ListFormat<String> listFormat = str -> listFormatString.replace("%value%", str);

        Messenger.sendMessage(sender, "serverutils.commandinfo.header");
        FormatBuilder builder = FormatBuilder.create(format)
                .orderedKeys("%key%", "%value%")
                .add("Name", cmd.getName());
        if (cmd instanceof PluginIdentifiableCommand) {
            PluginIdentifiableCommand pc = (PluginIdentifiableCommand) cmd;
            builder.add("Plugin", pc.getPlugin().getName());
        }
        builder.add("Usage", cmd.getUsage())
                .add("Description", cmd.getDescription())
                .add("Aliases", ListBuilder.create(cmd.getAliases())
                        .format(listFormat)
                        .seperator(seperator)
                        .lastSeperator(lastSeperator)
                        .toString())
                .add("Label", cmd.getLabel())
                .add("Timings Name", cmd.getTimingName())
                .add("Permission", cmd.getPermission())
                .add("Permission Message", cmd.getPermissionMessage());

        builder.sendTo(sender);
        Messenger.sendMessage(sender, "serverutils.commandinfo.footer");
    }
}
