package cat.nyaa.HamsterEcoHelper.auction;

import cat.nyaa.HamsterEcoHelper.HamsterEcoHelper;
import cat.nyaa.HamsterEcoHelper.I18n;
import cat.nyaa.HamsterEcoHelper.utils.EconomyUtil;
import cat.nyaa.HamsterEcoHelper.utils.Utils;
import cat.nyaa.utils.Message;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import static cat.nyaa.HamsterEcoHelper.utils.Utils.uid;

public class AuctionInstance {
    public long currentHighPrice = -1;
    public ItemStack itemStack;
    public int startPr;
    public int stepPr;
    public int timeout;
    public boolean hideName;
    public OfflinePlayer owner = null;
    public int reservePrice = 0;
    private HamsterEcoHelper plugin;
    private Runnable finishCallback;
    private int stage = 0;
    private OfflinePlayer currentPlayer = null;
    private CheckPointListener checkPointListener;
    private String itemName;
    public AuctionInstance(OfflinePlayer player, ItemStack itemToGive, int startPrice, int stepPrice, int reservePrice, int timeout, boolean hideName, HamsterEcoHelper plugin, Runnable finishCallback) {
        itemStack = itemToGive;
        startPr = startPrice;
        stepPr = stepPrice;
        this.timeout = timeout;
        this.plugin = plugin;
        this.finishCallback = finishCallback;
        this.hideName = hideName;
        this.owner = player;
        this.reservePrice = reservePrice;

        String realName;
        if (itemToGive.hasItemMeta() && itemToGive.getItemMeta().hasDisplayName()) {
            realName = itemToGive.getItemMeta().getDisplayName();
        } else {
            realName = itemToGive.getType().name() + ":" + itemToGive.getDurability();
        }

        if (hideName) {
            new Message(I18n._("user.auc.new_auction_unknown", startPrice, stepPrice, (int) Math.floor(timeout / 20D))).broadcast("heh.bid");
            itemName = I18n._("user.auc.mystery_item_placeholder");
        } else {
            if (owner == null) {
                new Message(I18n._("user.auc.new_auction_0")).append(itemToGive)
                        .appendFormat(plugin.i18n, "user.auc.new_auction_1", startPrice, stepPrice, (int) Math.floor(timeout / 20D))
                        .broadcast();
            } else {
                new Message(I18n._("user.auc.player_auction_0", owner.getName())).append(itemToGive)
                        .appendFormat(plugin.i18n, "user.auc.player_auction_1", startPrice, stepPrice, (int) Math.floor(timeout / 20D))
                        .broadcast();
            }
            itemName = realName;
        }
        if (owner == null) {
            plugin.logger.info(I18n._("log.info.auc_start", realName, itemToGive.getAmount(),
                    Boolean.toString(hideName), startPrice, stepPrice, uid(this)));
        } else {
            realName = "";
            if (itemToGive.hasItemMeta() && itemToGive.getItemMeta().hasDisplayName()) {
                realName = itemToGive.getItemMeta().getDisplayName();
            }
            if (realName.length() == 0) {
                realName = itemToGive.getType().name() + ":" + itemToGive.getDurability();
            } else {
                realName += "(" + itemToGive.getType().name() + ":" + itemToGive.getDurability() + ")";
            }
            long id = plugin.database.addItemLog(player, itemStack, startPrice, itemStack.getAmount());
            plugin.logger.info(I18n._("log.info.player_auc_start", id, player.getName(), realName, itemToGive.getAmount(),
                    Boolean.toString(hideName), startPrice, stepPrice, uid(this)));
        }
        checkPointListener = new CheckPointListener();
        checkPointListener.runTaskLater(plugin, timeout);
    }

    public boolean onBid(Player p, int price) {
        currentHighPrice = price;
        currentPlayer = p;
        Message msg = new Message(I18n._("user.auc.new_price_0", p.getName()));
        if (hideName) {
            msg.appendFormat(plugin.i18n, "user.auc.mystery_item_placeholder");
        } else {
            msg.append(itemStack);
        }
        msg.appendFormat(plugin.i18n, "user.auc.new_price_1", price).broadcast();
        p.sendMessage(I18n._("user.auc.new_price_success"));
        plugin.logger.info(I18n._("log.info.auc_bid", uid(this), p.getName(), price));
        stage = 0;
        checkPointListener.resetTime();
        return true;
    }

    public void finish() {
        EconomyUtil e = plugin.eco;
        if (currentPlayer == null) {
            if (this.owner != null && this.itemStack != null) {
                Utils.giveItem(this.owner, this.itemStack);
            }
            new Message(I18n._("user.auc.fail")).append(itemStack).broadcast();
            plugin.logger.info(I18n._("log.info.auc_finish", uid(this), currentHighPrice, "", "NO_PLAYER_BID"));
        } else {
            boolean success = false;
            if(owner != null) {
                plugin.auctionManager.cooldown.put(owner.getUniqueId(), System.currentTimeMillis() + (plugin.config.playerAuctionCooldownTicks * 50));
            }
            if (currentHighPrice >= reservePrice) {
                success = e.withdraw(currentPlayer, currentHighPrice);
                if (success && this.owner != null) {
                    if (plugin.config.playerAuctionCommissionFee > 0) {
                        double commissionFee = (currentHighPrice / 100) * plugin.config.playerAuctionCommissionFee;
                        e.deposit(this.owner, currentHighPrice - commissionFee);
                        plugin.balanceAPI.deposit(commissionFee);
                    } else {
                        e.deposit(this.owner, currentHighPrice);
                    }
                }
            }
            if (success) {
                int stat = Utils.giveItem(currentPlayer, itemStack);
                if (currentPlayer.isOnline() && currentPlayer instanceof Player) {
                    ((Player) currentPlayer).sendMessage(I18n._("user.auc.item_given_" + Integer.toString(stat)));
                }
                new Message(I18n._("user.auc.success_0")).append(itemStack)
                        .appendFormat(plugin.i18n, "user.auc.success_1", currentPlayer.getName())
                        .broadcast();
                plugin.logger.info(I18n._("log.info.auc_finish", uid(this), currentHighPrice, currentPlayer.getName(), "SUCCESS"));
                if (this.owner == null && plugin.balanceAPI.isEnabled()) {
                    plugin.balanceAPI.deposit(currentHighPrice);
                    plugin.config.saveToPlugin();
                    plugin.logger.info(I18n._("log.info.current_balance", plugin.balanceAPI.getBalance()));
                }
            } else {
                if (this.owner != null && this.itemStack != null) {
                    Utils.giveItem(this.owner, this.itemStack);
                }
                new Message(I18n._("user.auc.fail")).append(itemStack).broadcast();
                plugin.logger.info(I18n._("log.info.auc_finish", uid(this), currentHighPrice, currentPlayer.getName(), "NOT_ENOUGH_MONEY"));
            }
        }
        finishCallback.run();
    }

    public void halt() {
        if (this.owner != null && this.itemStack != null) {
            Utils.giveItem(this.owner, this.itemStack);
            this.itemStack = null;
            this.owner = null;
        }
        checkPointListener.cancel();
        plugin.logger.info(I18n._("log.info.auc_finish", uid(this), -1, "", "HALTED"));
    }

    private class CheckPointListener extends BukkitRunnable {
        @Override
        public void run() {
            switch (stage) {
                case 0:
                    if (currentHighPrice >= 0)
                        new Message(I18n._("user.auc.first", currentHighPrice)).broadcast("heh.bid");
                    stage = 1;
                    checkPointListener = new CheckPointListener();
                    checkPointListener.runTaskLater(plugin, timeout);
                    break;
                case 1:
                    if (currentHighPrice >= 0)
                        new Message(I18n._("user.auc.second", currentHighPrice)).broadcast("heh.bid");
                    stage = 2;
                    checkPointListener = new CheckPointListener();
                    checkPointListener.runTaskLater(plugin, timeout);
                    break;
                case 2:
                    if (currentHighPrice >= 0)
                        new Message(I18n._("user.auc.third", currentHighPrice)).broadcast("heh.bid");
                    finish();
                    /*
                    stage = 3;
                    checkPointListener = new CheckPointListener();
                    checkPointListener.runTaskLater(plugin, timeout);
                    break;
                case 3:
                    finish();*/
            }
        }

        public void resetTime() {
            this.cancel();
            checkPointListener = new CheckPointListener();
            checkPointListener.runTaskLater(plugin, timeout);
        }
    }

}
