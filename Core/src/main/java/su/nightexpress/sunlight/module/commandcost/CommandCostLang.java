package su.nightexpress.sunlight.module.commandcost;

import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.MessageLocale;
import su.nightexpress.sunlight.config.Lang;

import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.*;
import static su.nightexpress.sunlight.SLPlaceholders.GENERIC_AMOUNT;

public class CommandCostLang extends Lang {

    public static final MessageLocale ECONOMY_UNAVAILABLE = LangEntry.builder("CommandCost.Error.EconomyUnavailable").chatMessage(
        SOFT_RED.wrap("This command costs " + WHITE.wrap(GENERIC_AMOUNT) + ", but the server economy is unavailable.")
    );

    public static final MessageLocale INSUFFICIENT_FUNDS = LangEntry.builder("CommandCost.Error.InsufficientFunds").chatMessage(
        SOFT_RED.wrap("You need " + WHITE.wrap(GENERIC_AMOUNT) + " to use this command.")
    );

    public static final MessageLocale CHARGE_NOTIFY = LangEntry.builder("CommandCost.Charge.Notify").chatMessage(
        GRAY.wrap("You paid " + SOFT_YELLOW.wrap(GENERIC_AMOUNT) + " to use this command.")
    );
}
