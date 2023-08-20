package org.zyao97323.verify;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.io.File;
import java.io.IOException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Verify extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private Connection connection;
    private final Set<UUID> verifiedPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        extractLanguageFiles();
        // 注册指令执行者
        this.getCommand("verify").setExecutor(new VerifyCommand());
        // 注册Tab补全拦截器
        this.getCommand("verify").setTabCompleter(new VerifyCommandTabCompleter());

        // 注册玩家加入游戏事件监听器
        getServer().getPluginManager().registerEvents(this, this);

        // 定时执行提醒消息
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    if (!verifiedPlayers.contains(player.getUniqueId())) {
                        String pleaseverify = getLocalizedString("please_verify");
                        player.sendMessage(pleaseverify);
                    }
                }
            }
        }.runTaskTimer(this, 100, 100); // 100 ticks = 5 seconds

        // 加载配置文件
        loadConfig();
    }

    private void extractLanguageFiles() {
        // 获取插件jar包中的所有资源文件
        List<String> resourceFiles = Arrays.asList("zh_cn.yml", "en_us.yml"); // 以及其他需要提取的语言文件

        for (String resourceFile : resourceFiles) {
            // 如果文件不存在于插件数据文件夹中，则提取
            if (!getDataFolder().toPath().resolve(resourceFile).toFile().exists()) {
                saveResource(resourceFile, false); // 第二个参数为 false 表示不覆盖已存在的文件
            }
        }
    }

    public class VerifyCommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if ("verify".equalsIgnoreCase(cmd.getName())) {
                // 如果是 verify 命令，返回一个空的Tab补全列表，阻止Tab补全
                return new ArrayList<>();
            }
            return null; // 允许其他命令的Tab补全
        }
    }

    private void loadConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void saveConfig() {
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initDatabaseConnection() {
        boolean useMySQL = getConfig().getBoolean("mysql.enable");
        if (useMySQL) {
            initMySQLConnection();
        } else {
            initSQLiteConnection();
        }
    }

    private void initSQLiteConnection() {
        try {
            // 注册SQLite驱动
            Class.forName("org.sqlite.JDBC");

            String databaseFilename = getConfig().getString("sqlitedatabase-filename");
            // 连接到SQLite数据库文件
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/" + databaseFilename);
            // 创建表格（如果不存在）
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS player_date (player_name TEXT, birthdate TEXT, validation_server TEXT)");
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }
    }

    private void initMySQLConnection() {
        String host = getConfig().getString("mysql.host");
        int port = getConfig().getInt("mysql.port");
        String database = getConfig().getString("mysql.database");
        String user = getConfig().getString("mysql.user");
        String password = getConfig().getString("mysql.password");

        try {
            // 注册MySQL驱动
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 连接到MySQL数据库
            connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, user, password);
            // 创建表格（如果不存在）
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + getConfig().getString("mysql.table") + " (player_name VARCHAR(255), birthdate VARCHAR(8), validation_server VARCHAR(255))");
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 初始化数据库连接
        initDatabaseConnection();
        Verify pluginInstance = Verify.this;
        Player player = event.getPlayer();
        String birthdateStr = pluginInstance.getPlayerBirthdate(player.getName());
        Connection connection = null;
        PreparedStatement statement = null;
        if (birthdateStr != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            String currentServerName = getConfig().getString("server-name");
            String validationServer = getPlayerValidationServer(player.getName());
            if (!currentServerName.equals(validationServer)) {
                String othervalidationserver = getLocalizedString("other_validation_server").replace("{validationserver}", String.valueOf(validationServer));
                player.sendMessage(othervalidationserver);
                verifiedPlayers.add(player.getUniqueId());
                try {
                    Date birthDate = sdf.parse(birthdateStr);
                    long currentTimeMillis = System.currentTimeMillis();
                    int age = (int) ((currentTimeMillis - birthDate.getTime()) / (1000L * 60 * 60 * 24 * 365));

                    if (age >= 18) {
                        // 恢复玩家的移动能力
                        player.setWalkSpeed(0.2f);
                    } else {
                        int currentHour = Integer.parseInt(new SimpleDateFormat("HH").format(new Date()));
                        if (currentHour < 20 || currentHour >= 21) {
                            String minorKickMessage = getLocalizedString("minor_playtime_kick").replace("{age}", String.valueOf(age));
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    player.kickPlayer(minorKickMessage);
                                }
                            }.runTaskLater(pluginInstance, 60); // 60 ticks = 3 seconds (20 ticks per second)
                        }
                    }
                } catch (ParseException e) {
                    player.sendMessage(getLocalizedString("error_birthdate"));
                }
            } else {
                try {
                    Date birthDate = sdf.parse(birthdateStr);
                    long currentTimeMillis = System.currentTimeMillis();
                    int age = (int) ((currentTimeMillis - birthDate.getTime()) / (1000L * 60 * 60 * 24 * 365));

                    if (age >= 18) {
                        // 恢复玩家的移动能力
                        player.setWalkSpeed(0.2f);
                    } else {
                        int currentHour = Integer.parseInt(new SimpleDateFormat("HH").format(new Date()));
                        if (currentHour < 20 || currentHour >= 21) {
                            String minorKickMessage = getLocalizedString("minor_playtime_kick").replace("{age}", String.valueOf(age));
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    player.kickPlayer(minorKickMessage);
                                }
                            }.runTaskLater(pluginInstance, 60); // 60 ticks = 3 seconds (20 ticks per second)
                        }
                    }
                } catch (ParseException e) {
                    player.sendMessage(getLocalizedString("error_birthdate"));
                } finally {
                    try {
                        if (statement != null) {
                            statement.close();
                        }
                        if (connection != null) {
                            connection.close();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            player.setWalkSpeed(0); // 限制移动速度为0
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 检查玩家是否已经完成验证
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            // 如果未验证，取消玩家的移动事件
            event.setCancelled(true);
        }
    }

    public boolean reloadConfig(CommandSender sender) {
        if (sender.hasPermission("verify.reload")) {
            // 重新加载配置文件
            JavaPlugin plugin = this; // Assuming you are inside the Verify class
            plugin.reloadConfig();
            String reloadconfig = getLocalizedString("reload_config");
            sender.sendMessage(reloadconfig);

            // 重载数据库（如果使用了数据库）
            reloadDatabase(plugin);
            String reloaddatabase = getLocalizedString("reload_database");
            sender.sendMessage(reloaddatabase);

            return true;
        } else {
            String nopermission = getLocalizedString("no_permission");
            sender.sendMessage(nopermission);
            return false;
        }
    }

    private void reloadDatabase(JavaPlugin plugin) {
        // 关闭当前的数据库连接
        closeDatabaseConnection();

        // 重新初始化数据库连接
        initDatabaseConnection();

        // 这里您可以添加其他重新加载数据的操作，如重新读取数据到内存等
    }

    private void closeDatabaseConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public class VerifyCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                String onlyplayer = getLocalizedString("only_player");
                sender.sendMessage(onlyplayer);
                return true;
            }

            // 检查命令是否有参数
            if (args.length == 0) {
                String usage = getLocalizedString("usage");
                sender.sendMessage(usage);
                return true;
            }
            // 在命令中添加重新加载配置文件的逻辑
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("verify.reload")) {
                    reloadConfig(sender);
                } else {
                    String nopermission = getLocalizedString("no_permission");
                    sender.sendMessage(nopermission);
                }
                return true;
            }

            // 获取玩家对象
            Player player = (Player) sender;

            // 获取输入的身份证号码
            String idNumber = args[0];

            // 检查身份证号码是否为18位
            if (idNumber.length() != 18) {
                String errorid = getLocalizedString("error_id");
                player.sendMessage(errorid);
                return true;
            }

            // 使用提供的算法验证身份证号码合法性
            boolean isValid = validateID(idNumber);

            if (isValid) {
                // 获取出生日期并计算年龄
                String birthDateStr = idNumber.substring(6, 14);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                try {
                    Date birthDate = sdf.parse(birthDateStr);
                    long currentTimeMillis = System.currentTimeMillis();
                    int age = (int) ((currentTimeMillis - birthDate.getTime()) / (1000L * 60 * 60 * 24 * 365));

                    if (age >= 18) {
                        String verificationMessage = getLocalizedString("verification_successful").replace("{age}", String.valueOf(age));
                        player.sendMessage(verificationMessage);
                    } else {
                        String minorLimitedMessage = getLocalizedString("minor_playtime_limited").replace("{age}", String.valueOf(age));
                        player.sendMessage(minorLimitedMessage);

                        int currentHour = Integer.parseInt(new SimpleDateFormat("HH").format(new Date()));
                        if (currentHour < 20 || currentHour >= 21) {
                            String minorKickMessage = getLocalizedString("minor_playtime_kick").replace("{age}", String.valueOf(age));
                            player.kickPlayer(minorKickMessage);
                        }
                    }
                    // 获取当前服务器名称
                    String currentServerName = getConfig().getString("server-name");
                    try {
                        PreparedStatement statement = null;
                        if (getConfig().getBoolean("mysql.enable")) {
                            // MySQL插入
                            statement = connection.prepareStatement("INSERT INTO " + getConfig().getString("mysql.table") + " (player_name, birthdate, validation_server) VALUES (?, ?, ?)");
                        } else {
                            // SQLite插入
                            statement = connection.prepareStatement("INSERT INTO player_date (player_name, birthdate, validation_server) VALUES (?, ?, ?)");
                        }
                        statement.setString(1, player.getName());
                        statement.setString(2, birthDateStr);
                        statement.setString(3, currentServerName);
                        statement.executeUpdate();
                        statement.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                } catch (ParseException e) {
                    String errorbirthdate = getLocalizedString("error_birthdate");
                    player.sendMessage(errorbirthdate);
                }
            } else {
                String voidid = getLocalizedString("void_id");
                player.sendMessage(voidid);
                return true;
            }
            // 验证完成后，将玩家添加到验证通过的列表，并恢复移动能力
            if (sender instanceof Player) {
                verifiedPlayers.add(player.getUniqueId());
                String validationcomplete = getLocalizedString("validation_complete");
                player.sendMessage(validationcomplete);
                player.setWalkSpeed(0.2f); // 恢复默认移动速度
            }

            return true;
        }

        private boolean validateID(String idNumber) {
            // 计算校验码
            int[] coefficient = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
            char[] validationChar = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

            int sum = 0;
            for (int i = 0; i < 17; i++) {
                int digit = Character.getNumericValue(idNumber.charAt(i));
                sum += digit * coefficient[i];
            }

            int remainder = sum % 11;
            char expectedValidationChar = validationChar[remainder];

            return expectedValidationChar == idNumber.charAt(17);
        }
    }

    public String getPlayerBirthdate(String playerName) {
        try {
            PreparedStatement statement = null;
            if (getConfig().getBoolean("mysql.enable")) {
                // MySQL插入
                statement = connection.prepareStatement("SELECT birthdate FROM " + getConfig().getString("mysql.table") + " WHERE player_name = ?");
            } else {
                // SQLite插入
                statement = connection.prepareStatement("SELECT birthdate FROM player_date WHERE player_name = ?");
            }
            statement.setString(1, playerName);
            ResultSet resultSet = statement.executeQuery();

            if (resultSet.next()) {
                return resultSet.getString("birthdate");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getPlayerValidationServer(String playerName) {
        try {
            PreparedStatement statement = null;
            if (getConfig().getBoolean("mysql.enable")) {
                // MySQL插入
                statement = connection.prepareStatement("SELECT validation_server FROM " + getConfig().getString("mysql.table") + " WHERE player_name = ?");
            } else {
                // SQLite插入
                statement = connection.prepareStatement("SELECT validation_server FROM player_date WHERE player_name = ?");
            }
            statement.setString(1, playerName);
            ResultSet resultSet = statement.executeQuery();

            if (resultSet.next()) {
                return resultSet.getString("validation_server");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }

    private String getLocalizedString(String key) {
        String lang = getConfig().getString("lang");
        File langFile = new File(getDataFolder() + "/", lang + ".yml");
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);

        String localizedString = langConfig.getString(key);
        System.out.println("Localized String for key '" + key + "': " + localizedString);
        return localizedString;
    }
}
