package com.nite07;

import com.nite07.Pojo.RssItem;
import com.nite07.Pojo.Entry;
import kotlin.Pair;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin;
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescriptionBuilder;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.event.GlobalEventChannel;
import net.mamoe.mirai.event.events.*;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.PlainText;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public final class RssBot extends JavaPlugin {
    public static final RssBot INSTANCE = new RssBot();
    public Config cfg = null;
    Map<Long, ScheduledFuture<?>> tasks = new HashMap<>();
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(Integer.MAX_VALUE);
    Bot myBot = null;

    private RssBot() {
        super(new JvmPluginDescriptionBuilder("com.nite07.RssBot", "1.7")
                .name("RssBot")
                .info("A Rss Bot")
                .author("Nite07")
                .build());
    }

    /**
     * 将 String 转换为 long,如果不能转换返回 -1
     *
     * @param s String
     * @return long
     */
    static public long strToLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 插件加载事件(监听消息)
     */
    @Override
    public void onEnable() {
        getLogger().info("RssBot 插件已加载，初次启动后请修改配置(config\\RssBot\\config.json)");
        cfg = new Config(getLogger());
        // 监听消息
        GlobalEventChannel.INSTANCE.subscribeAlways(MessageEvent.class, g -> new Thread(() -> runCMD(g.getMessage().contentToString(), g)).start());
        GlobalEventChannel.INSTANCE.subscribeOnce(BotOnlineEvent.class, g -> new Thread(() -> {
            myBot = getBotInstance();
            if (myBot != null) {
                getLogger().info("RssBot使用账号已登录，若在使用中出现问题请给我留言（https://www.nite07.com/rssbot）");
                loadTasks();
            }
        }).start());

        //监听好友请求
        GlobalEventChannel.INSTANCE.subscribeAlways(NewFriendRequestEvent.class, g -> new Thread(() -> {
            if (cfg.getAutoAcceptFriendApplication()) {
                g.accept();
            }
        }).start());

        //监听群邀请
        GlobalEventChannel.INSTANCE.subscribeAlways(BotInvitedJoinGroupRequestEvent.class, g -> new Thread(() -> {
            if (cfg.getAutoAcceptGroupApplication()) {
                g.accept();
            }
        }).start());

        //监听退群事件
        GlobalEventChannel.INSTANCE.subscribeAlways(BotLeaveEvent.class, g -> new Thread(() -> cfg.clearConfigItem(String.valueOf(g.getGroup().getId()), "Group")).start());

        //监听好友删除事件
        GlobalEventChannel.INSTANCE.subscribeAlways(FriendDeleteEvent.class, g -> new Thread(() -> cfg.clearConfigItem(String.valueOf(g.getFriend().getId()), "Friend")).start());
    }

    /**
     * 获取消息的来源类型
     *
     * @param g MessageEvent
     * @return 好友消息返回"Friend",群消息返回"Group",其他消息返回"Other"
     */
    public String getMessageType(MessageEvent g) {
        if (g.getSubject() instanceof Group) {
            return "Group";
        } else if (g.getSubject() instanceof Friend) {
            return "Friend";
        }
        return "Other";
    }

    /**
     * 检查发送者是否有操作权限
     *
     * @param g MessageEvent
     * @return boolean
     */
    public boolean checkSenderPerm(MessageEvent g) {
        if (cfg.whiteList()) {
            return cfg.inWhiteList(String.valueOf(g.getSender().getId()));
        } else if (cfg.groupPermissionRestrictions()) {
            if (g.getSubject() instanceof Group) {
                MemberPermission sender;
                try {
                    sender = ((GroupMessageEvent) g).getPermission();
                } catch (Exception ignore) {
                    sender = ((Group) g.getSubject()).getBotPermission();
                }
                return sender == MemberPermission.OWNER || sender == MemberPermission.ADMINISTRATOR;
            } else return g.getSubject() instanceof Friend;
        } else {
            return true;
        }
    }

    /**
     * 检测 RssUrl 是否可访问
     *
     * @param url RssUrl
     * @return boolean
     */
    public boolean checkUrl(String url) {
        String credential = cfg.getProxyCredential();
        OkHttpClient okHttpClient = new OkHttpClient().newBuilder().connectTimeout(5, TimeUnit.SECONDS).proxy(cfg.getProxy()).build();
        Request request;
        if (credential != null) {
            request = new Request.Builder().url(url).addHeader("Proxy-Authorization", credential).build();
        } else {
            request = new Request.Builder().url(url).build();
        }
        Response response;
        try {
            response = okHttpClient.newCall(request).execute();
            return response.isSuccessful();
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * 判断参数是否为数字
     *
     * @param s String
     * @return boolean
     */
    public boolean isDigit(String s) {
        boolean flag = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                flag = false;
            }
        }
        return flag;
    }

    /**
     * 判断发信者是否是订阅的合法管理者
     *
     * @param g MessageEvent
     * @param c ConfigItem
     * @return boolean
     */
    public boolean isSubOwner(MessageEvent g, RssItem c) {
        return String.valueOf(g.getSubject().getId()).equals(c.target);
    }

    /**
     * 被动发送信息
     *
     * @param g   MessageEvent
     * @param msg String 信息内容
     */
    public void sendMessage(MessageEvent g, String msg) {
        if (getMessageType(g).equals("Group")) {
            PlainText t = new PlainText("\n" + msg);
            At at = new At(g.getSender().getId());
            MessageChain mc = at.plus(t);
            g.getSubject().sendMessage(mc);
        }
        if (getMessageType(g).equals("Friend")) {
            PlainText t = new PlainText(msg);
            g.getSender().sendMessage(t);
        }
    }

    /**
     * 获取Bot实例
     *
     * @return Bot
     */
    public Bot getBotInstance() {
        for (Bot bot : Bot.getInstances()) {
            if (String.valueOf(bot.getId()).equals(cfg.getBotId())) {
                return bot;
            }
        }
        return null;
    }

    /**
     * 插件启用后添加 Tasks
     */
    public void loadTasks() {
        List<RssItem> cis = cfg.getRssItems();
        if (cis != null) {
            for (RssItem c : cis) {
                tasks.put(c.id, executor.scheduleWithFixedDelay(new Scheduler(c, myBot, getLogger(), cfg), 0, c.interval, TimeUnit.MINUTES));
            }
        }
    }

    /**
     * 命令处理方法
     *
     * @param cmd 命令行
     * @param g   MessageEvent
     */
    public void runCMD(String cmd, MessageEvent g) {
        cmd = cmd.trim();
        boolean isBotAdmin = cfg.isBotAdmin(String.valueOf(g.getSender().getId()));
        if (cmd.startsWith("#")) {
            String[] slice = cmd.split(" ");
            int paramNum = slice.length - 1;
            if (cmd.startsWith("#sub")) {
                if (checkSenderPerm(g) || isBotAdmin) {
                    if (checkUrl(slice[1])) {
                        if (paramNum == 1) {
                            if (cfg.reachLimit()) {
                                long id = cfg.getNewId();
                                String xml = Rss.get(slice[1], cfg);
                                Pair<String, List<Entry>> p = Rss.parseXML(xml);
                                if (p == null) {
                                    getLogger().warning("添加订阅发生异常，订阅链接为：" + slice[1]);
                                    sendMessage(g, "添加订阅发生异常");
                                    return;
                                }
                                RssItem c = new RssItem(id, getMessageType(g), String.valueOf(g.getSubject().getId()), slice[1], 10, p.getFirst(), p.getSecond());
                                cfg.addRssItem(c);
                                tasks.put(id, executor.scheduleWithFixedDelay(new Scheduler(c, myBot, getLogger(), cfg), 0, 10, TimeUnit.MINUTES));
                                sendMessage(g, "订阅设置成功\nID：" + id + "\n标题：" + c.title + "\n链接：" + c.url + "\n抓取频率：10分钟");
                            } else {
                                sendMessage(g, "总订阅量已达上限，你可以自己搭建一个RssBot\nhttps://www.nite07.com/rssbot");
                            }
                        } else if (paramNum == 2) {
                            if (isDigit(slice[2])) {
                                if (cfg.reachLimit()) {
                                    long id = cfg.getNewId();
                                    String xml = Rss.get(slice[1], cfg);
                                    Pair<String, List<Entry>> p = Rss.parseXML(xml);
                                    if (p == null) {
                                        getLogger().warning("添加订阅发生异常，订阅链接为：" + slice[1]);
                                        sendMessage(g, "添加订阅发生异常");
                                        return;
                                    }
                                    RssItem c = new RssItem(id, getMessageType(g), String.valueOf(g.getSubject().getId()), slice[1], Integer.parseInt(slice[2]), p.getFirst(), p.getSecond());
                                    cfg.addRssItem(c);
                                    tasks.put(id, executor.scheduleWithFixedDelay(new Scheduler(c, myBot, getLogger(), cfg), 0, Integer.parseInt(slice[2]), TimeUnit.MINUTES));
                                    sendMessage(g, "订阅设置成功\nID：" + id + "\n标题：" + c.title + "\n链接：" + c.url + "\n抓取频率：" + slice[2] + "分钟");
                                } else {
                                    sendMessage(g, "总订阅量已达上限，你可以自己搭建一个RssBot\nhttps://www.nite07.com/rssbot");
                                }
                            } else {
                                sendMessage(g, "参数错误");
                            }
                        } else {
                            sendMessage(g, "参数错误");
                        }
                    } else {
                        sendMessage(g, "链接无法访问");
                    }
                } else {
                    sendMessage(g, "没有操作权限");
                }
            } else if (cmd.startsWith("#unsub")) {
                if (checkSenderPerm(g) || isBotAdmin) {
                    if (paramNum == 1 && strToLong(slice[1]) != -1) {
                        RssItem c = cfg.getRssItem(Long.parseLong(slice[1]));
                        if (c != null) {
                            if (isSubOwner(g, c)) {
                                tasks.get(strToLong(slice[1])).cancel(true);
                                tasks.remove(strToLong(slice[1]));
                                cfg.removeConfigItem(c);
                                sendMessage(g, "订阅已取消\n" + "ID：" + c.id + "\n标题：" + c.title + "\n链接：" + c.url);
                            } else {
                                sendMessage(g, "没有操作权限");
                            }
                        } else {
                            sendMessage(g, "未找到订阅");
                        }
                    } else {
                        sendMessage(g, "参数错误");
                    }
                } else {
                    sendMessage(g, "没有操作权限");
                }
            } else if (cmd.startsWith("#setinterval")) {
                if (checkSenderPerm(g) || isBotAdmin) {
                    if (paramNum == 2 && strToLong(slice[1]) != -1 && strToLong(slice[2]) != -1) {
                        RssItem c = cfg.getRssItem(Long.parseLong(slice[1]));
                        if (c != null) {
                            tasks.get(strToLong(slice[1])).cancel(true);
                            tasks.put(strToLong(slice[1]), executor.scheduleWithFixedDelay(new Scheduler(c, myBot, getLogger(), cfg), 0, strToLong(slice[2]), TimeUnit.MINUTES));
                            c.interval = Integer.parseInt(slice[2]);
                            cfg.saveData();
                            sendMessage(g, "抓取频率已修改\n" + "ID：" + c.id + "\n标题：" + c.title + "\n链接：" + c.url + "\n抓取频率：" + c.interval + "分钟");
                        } else {
                            sendMessage(g, "未找到订阅");
                        }
                    } else {
                        sendMessage(g, "参数错误");
                    }
                } else {
                    sendMessage(g, "没有操作权限");
                }
            } else if (cmd.equals("#list")) {
                if (checkSenderPerm(g) || isBotAdmin) {
                    if (paramNum == 0) {
                        List<RssItem> cs;
                        if (isBotAdmin) {
                            cs = cfg.getRssItems();
                        } else {
                            cs = cfg.getOnesConfigItems(String.valueOf(g.getSubject().getId()));
                        }
                        if (cs.size() != 0) {
                            StringBuilder stringBuilder = new StringBuilder();
                            stringBuilder.append("当前有").append(cs.size()).append("条订阅:\n");
                            for (RssItem c : cs) {
                                stringBuilder.append("\nID：").append(c.id).append("\n标题：").append(c.title).append("\n链接：").append(c.url).append("\n");
                            }
                            sendMessage(g, stringBuilder.toString());
                        } else {
                            sendMessage(g, "当前无订阅");
                        }
                    } else {
                        sendMessage(g, "参数错误");
                    }
                } else {
                    sendMessage(g, "没有操作权限");
                }
            } else if (cmd.equals("#help")) {
                String t = "#sub <url> [interval(minute)]\n添加订阅\n" +
                        "#unsub <id>\n取消订阅\n" +
                        "#setinterval <id> <interval(minute)>\n设置抓取间隔（单位：分钟）\n" +
                        "#list\n列出当前订阅\n" +
                        "#detail <id>\n查询订阅详细信息\n" +
                        "<>为必须参数，[]为可选参数";
                sendMessage(g, t);
            } else if (cmd.startsWith("#detail")) {
                if (checkSenderPerm(g)) {
                    if (paramNum == 1) {
                        if (strToLong(slice[1]) != -1) {
                            RssItem c = cfg.getRssItem(strToLong(slice[1]));
                            sendMessage(g, "ID：" + c.id + "\n标题：" + c.title + "\n链接：" + c.url + "\n抓取频率：" + c.interval + "分钟\n");
                        } else {
                            sendMessage(g, "参数错误");
                        }
                    } else {
                        sendMessage(g, "参数错误");
                    }
                } else {
                    sendMessage(g, "没有操作权限");
                }
            } else if (cmd.startsWith("#status")) {
                if (isBotAdmin) {
                    sendMessage(g, cfg.getLog());
                } else {
                    sendMessage(g, "没有操作权限");
                }
            }
        }
    }
}

