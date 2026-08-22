package su.nightexpress.sunlight.module.commandcost;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.nightcore.bridge.currency.Currency;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.integration.currency.EconomyBridge;
import su.nightexpress.nightcore.integration.VaultHook;
import su.nightexpress.nightcore.util.LowerCase;
import su.nightexpress.nightcore.util.NumberUtil;
import su.nightexpress.sunlight.SLPlaceholders;
import su.nightexpress.sunlight.command.CommandKey;
import su.nightexpress.sunlight.command.CommandRegistry;
import su.nightexpress.sunlight.command.provider.CommandProvider;
import su.nightexpress.sunlight.command.provider.definition.HubDefinition;
import su.nightexpress.sunlight.command.provider.definition.LiteralDefinition;
import su.nightexpress.sunlight.config.PermissionTree;
import su.nightexpress.sunlight.config.Perms;
import su.nightexpress.sunlight.hook.placeholder.PlaceholderRegistry;
import su.nightexpress.sunlight.module.Module;
import su.nightexpress.sunlight.module.ModuleContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Charges registered SunLight command nodes using their provider and node IDs. */
public class CommandCostModule extends Module {

    private static final String PATH_NOTIFY = "Notify_On_Charge";
    private static final String PATH_COSTS = "Costs";
    private static final String FIELD_COST = "Cost";
    private static final String FIELD_DEFAULT = "Default";
    private static final String FIELD_CHILDREN = "Children";

    private boolean notifyOnCharge;
    private Map<CommandKey, Double> standaloneCosts;
    private Map<CommandKey, Double> hubDefaults;
    private Map<CommandKey, Map<String, Double>> hubChildren;

    public CommandCostModule(@NotNull ModuleContext context) {
        super(context);
        this.standaloneCosts = Map.of();
        this.hubDefaults = Map.of();
        this.hubChildren = Map.of();
    }

    @Override
    protected void loadModule(@NotNull FileConfig config) {
        this.addDefaults(config);
        this.plugin.injectLang(CommandCostLang.class);

        this.notifyOnCharge = config.getBoolean(PATH_NOTIFY, false);
        if (this.commandRegistry.isProvidersReady()) {
            this.loadCosts(config);
        }
    }

    @Override
    protected void unloadModule() {
        this.standaloneCosts = Map.of();
        this.hubDefaults = Map.of();
        this.hubChildren = Map.of();
    }

    @Override
    protected void registerPermissions(@NotNull PermissionTree root) {
        // The bypass permission is global and is registered by SunLight after all modules load.
    }

    @Override
    protected void registerCommands() {
        // Command interception is provided by CommandRegistry's execution pipeline.
    }

    @Override
    public void registerPlaceholders(@NotNull PlaceholderRegistry registry) {
        // No placeholders are exposed by this module.
    }

    /**
     * Charges a player for a registered command execution. A {@code null} child ID identifies a standalone literal;
     * otherwise the node ID identifies a hub and the child ID identifies its selected child literal.
     */
    public boolean charge(@NotNull Player player, @NotNull String providerId, @NotNull String nodeId, @Nullable String childId) {
        if (player.hasPermission(Perms.BYPASS_COMMAND_COST)) return true;

        CommandKey key = new CommandKey(normalize(providerId), normalize(nodeId));
        Double cost = childId == null ? this.standaloneCosts.get(key) : this.getHubCost(key, childId);
        if (cost == null || cost <= 0D) return true;

        if (!EconomyBridge.initialized() || !EconomyBridge.api().hasVaultCurrency() || !VaultHook.hasEconomy()) {
            this.sendPrefixed(CommandCostLang.ECONOMY_UNAVAILABLE, player, builder -> builder
                .with(SLPlaceholders.GENERIC_AMOUNT, () -> NumberUtil.format(cost)));
            return false;
        }

        Currency currency = EconomyBridge.api().getVaultCurrency();
        if (currency == null) {
            this.sendPrefixed(CommandCostLang.ECONOMY_UNAVAILABLE, player, builder -> builder
                .with(SLPlaceholders.GENERIC_AMOUNT, () -> NumberUtil.format(cost)));
            return false;
        }

        if (currency.queryBalance(player) < cost) {
            this.sendPrefixed(CommandCostLang.INSUFFICIENT_FUNDS, player, builder -> builder
                .with(SLPlaceholders.GENERIC_AMOUNT, () -> currency.format(cost)));
            return false;
        }

        currency.withdraw(player, cost);
        if (this.notifyOnCharge) {
            this.sendPrefixed(CommandCostLang.CHARGE_NOTIFY, player, builder -> builder
                .with(SLPlaceholders.GENERIC_AMOUNT, () -> currency.format(cost)));
        }
        return true;
    }

    /** Reloads rules after CommandRegistry has populated every provider's stable node IDs. */
    public void reloadCosts() {
        FileConfig config = this.getConfig();
        this.notifyOnCharge = config.getBoolean(PATH_NOTIFY, false);
        this.loadCosts(config);
    }

    @Nullable
    private Double getHubCost(@NotNull CommandKey key, @NotNull String childId) {
        Map<String, Double> children = this.hubChildren.get(key);
        if (children != null) {
            Double childCost = children.get(normalize(childId));
            if (childCost != null) return childCost;
        }
        return this.hubDefaults.get(key);
    }

    private void addDefaults(@NotNull FileConfig config) {
        config.addMissing(PATH_NOTIFY, false);
        config.setComments(PATH_NOTIFY, "Controls whether players are notified after a command cost is charged.");

        config.addMissing(PATH_COSTS, new LinkedHashMap<>());
        config.setComments(PATH_COSTS,
            "Configure command costs by stable provider and node IDs, never command aliases.",
            "Standalone literals use Cost. Hub children use Children.<child> before Default.",
            "A zero child cost explicitly makes that child free. Missing costs are free.",
            "Example:",
            "  item:",
            "    item:",
            "      Default: 10",
            "      Children:",
            "        repair: 100",
            "        name: 25",
            "  homes-common:",
            "    teleport:",
            "      Cost: 50");
    }

    private void loadCosts(@NotNull FileConfig config) {
        Map<CommandKey, Double> standalone = new LinkedHashMap<>();
        Map<CommandKey, Double> defaults = new LinkedHashMap<>();
        Map<CommandKey, Map<String, Double>> children = new LinkedHashMap<>();

        if (config.contains(PATH_COSTS) && config.getConfigurationSection(PATH_COSTS) == null) {
            this.warn("Skipping command costs: '%s' must be a configuration section.".formatted(PATH_COSTS));
            this.standaloneCosts = Map.of();
            this.hubDefaults = Map.of();
            this.hubChildren = Map.of();
            return;
        }

        for (String rawProviderId : config.getSection(PATH_COSTS)) {
            String providerId = normalize(rawProviderId);
            CommandProvider provider = this.commandRegistry.getProvider(providerId).orElse(null);
            if (provider == null) {
                this.warn("Skipping command-cost provider '%s': no registered SunLight provider has this ID.".formatted(rawProviderId));
                continue;
            }

            String providerPath = PATH_COSTS + "." + rawProviderId;
            if (config.getConfigurationSection(providerPath) == null) {
                this.warn("Skipping command-cost provider '%s': expected a configuration section.".formatted(rawProviderId));
                continue;
            }
            for (String rawNodeId : config.getSection(providerPath)) {
                String nodeId = normalize(rawNodeId);
                String nodePath = providerPath + "." + rawNodeId;
                if (config.getConfigurationSection(nodePath) == null) {
                    this.warn("Skipping command-cost node '%s.%s': expected a configuration section.".formatted(rawProviderId, rawNodeId));
                    continue;
                }
                boolean standaloneNode = this.isRegisteredLiteral(provider, nodeId);
                HubDefinition hubDefinition = provider.getRootDefinitions().get(nodeId);
                boolean hubNode = this.isRegisteredHub(provider, nodeId, hubDefinition);
                if (!standaloneNode && !hubNode) {
                    this.warn("Skipping command-cost node '%s.%s': it is not an enabled, registered literal or hub.".formatted(rawProviderId, rawNodeId));
                    continue;
                }

                CommandKey key = new CommandKey(providerId, nodeId);
                Set<String> allowedFields = standaloneNode && hubNode ? Set.of(FIELD_COST, FIELD_DEFAULT, FIELD_CHILDREN) :
                    (standaloneNode ? Set.of(FIELD_COST) : Set.of(FIELD_DEFAULT, FIELD_CHILDREN));
                for (String field : config.getSection(nodePath)) {
                    if (!allowedFields.contains(field)) {
                        this.warn("Skipping command-cost setting '%s.%s': it is not valid for this command node.".formatted(nodePath, field));
                    }
                }

                if (standaloneNode) {
                    this.readCost(config, nodePath + "." + FIELD_COST).ifPresent(cost -> standalone.put(key, cost));
                }
                if (hubNode) {
                    this.readCost(config, nodePath + "." + FIELD_DEFAULT).ifPresent(cost -> defaults.put(key, cost));

                    Map<String, Double> childCosts = new LinkedHashMap<>();
                    String childrenPath = nodePath + "." + FIELD_CHILDREN;
                    if (config.contains(childrenPath) && config.getConfigurationSection(childrenPath) == null) {
                        this.warn("Skipping command-cost children '%s': expected a configuration section.".formatted(childrenPath));
                        continue;
                    }
                    for (String rawChildId : config.getSection(childrenPath)) {
                        String childId = normalize(rawChildId);
                        String childAlias = hubDefinition.childrenAliases().get(childId);
                        if (!this.isRegisteredHubChild(provider, childId, childAlias)) {
                            this.warn("Skipping command-cost child '%s.%s.Children.%s': it is not an enabled, registered child for this hub.".formatted(rawProviderId, rawNodeId, rawChildId));
                            continue;
                        }

                        this.readCost(config, childrenPath + "." + rawChildId).ifPresent(cost -> childCosts.put(childId, cost));
                    }
                    if (!childCosts.isEmpty()) children.put(key, Map.copyOf(childCosts));
                }
            }
        }

        this.standaloneCosts = Map.copyOf(standalone);
        this.hubDefaults = Map.copyOf(defaults);
        this.hubChildren = Map.copyOf(children);
    }

    private boolean isRegisteredHub(@NotNull CommandProvider provider, @NotNull String nodeId, @Nullable HubDefinition definition) {
        return definition != null && definition.enabled() && CommandRegistry.hasUsableAlias(definition.aliases()) && provider.getRootBuilders().containsKey(nodeId) && definition
            .childrenAliases().entrySet().stream().anyMatch(entry -> this.isRegisteredHubChild(provider, entry.getKey(), entry.getValue()));
    }

    private boolean isRegisteredHubChild(@NotNull CommandProvider provider, @NotNull String childId, @Nullable String alias) {
        return alias != null && !alias.isBlank() && provider.getLiteralBuilders().containsKey(childId);
    }

    private boolean isRegisteredLiteral(@NotNull CommandProvider provider, @NotNull String nodeId) {
        LiteralDefinition definition = provider.getLiteralDefinitions().get(nodeId);
        return provider.getLiteralBuilders().containsKey(nodeId) && definition != null && definition.enabled() && CommandRegistry.hasUsableAlias(definition.aliases());
    }

    @NotNull
    private Optional<Double> readCost(@NotNull FileConfig config, @NotNull String path) {
        if (!config.contains(path)) return Optional.empty();

        Object raw = config.get(path);
        if (!(raw instanceof Number number)) {
            this.warn("Skipping command cost at '%s': expected a finite, non-negative number.".formatted(path));
            return Optional.empty();
        }

        double cost = number.doubleValue();
        if (!Double.isFinite(cost) || cost < 0D) {
            this.warn("Skipping command cost at '%s': expected a finite, non-negative number.".formatted(path));
            return Optional.empty();
        }
        return Optional.of(cost);
    }

    @NotNull
    private static String normalize(@NotNull String id) {
        return LowerCase.INTERNAL.apply(id);
    }
}
