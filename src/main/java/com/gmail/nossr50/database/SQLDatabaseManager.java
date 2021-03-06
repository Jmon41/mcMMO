package com.gmail.nossr50.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.datatypes.MobHealthbarType;
import com.gmail.nossr50.datatypes.database.DatabaseUpdateType;
import com.gmail.nossr50.datatypes.database.PlayerStat;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.datatypes.skills.AbilityType;
import com.gmail.nossr50.datatypes.skills.SkillType;
import com.gmail.nossr50.datatypes.spout.huds.HudType;
import com.gmail.nossr50.runnables.database.SQLReconnectTask;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.StringUtils;

public final class SQLDatabaseManager implements DatabaseManager {
    private String connectionString;
    private String tablePrefix = Config.getInstance().getMySQLTablePrefix();
    private Connection connection = null;

    // Scale waiting time by this much per failed attempt
    private final double SCALING_FACTOR = 40.0;

    // Minimum wait in nanoseconds (default 500ms)
    private final long MIN_WAIT = 500L * 1000000L;

    // Maximum time to wait between reconnects (default 5 minutes)
    private final long MAX_WAIT = 5L * 60L * 1000L * 1000000L;

    // How long to wait when checking if connection is valid (default 3 seconds)
    private final int VALID_TIMEOUT = 3;

    // When next to try connecting to Database in nanoseconds
    private long nextReconnectTimestamp = 0L;

    // How many connection attempts have failed
    private int reconnectAttempt = 0;

    protected SQLDatabaseManager() {
        checkConnected();
        createStructure();
    }

    public void purgePowerlessUsers() {
        mcMMO.p.getLogger().info("Purging powerless users...");

        Collection<ArrayList<String>> usernames = read("SELECT u.user FROM " + tablePrefix + "skills AS s, " + tablePrefix + "users AS u WHERE s.user_id = u.id AND (s.taming+s.mining+s.woodcutting+s.repair+s.unarmed+s.herbalism+s.excavation+s.archery+s.swords+s.axes+s.acrobatics+s.fishing) = 0").values();

        write("DELETE FROM u, e, h, s, c USING " + tablePrefix + "users u " +
                "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                "WHERE (s.taming+s.mining+s.woodcutting+s.repair+s.unarmed+s.herbalism+s.excavation+s.archery+s.swords+s.axes+s.acrobatics+s.fishing) = 0");

        processPurge(usernames);
        mcMMO.p.getLogger().info("Purged " + usernames.size() + " users from the database.");
    }

    public void purgeOldUsers() {
        long currentTime = System.currentTimeMillis();

        mcMMO.p.getLogger().info("Purging old users...");

        Collection<ArrayList<String>> usernames = read("SELECT user FROM " + tablePrefix + "users WHERE ((" + currentTime + " - lastlogin * " + Misc.TIME_CONVERSION_FACTOR + ") > " + PURGE_TIME + ")").values();

        write("DELETE FROM u, e, h, s, c USING " + tablePrefix + "users u " +
                "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                "WHERE ((" + currentTime + " - lastlogin * " + Misc.TIME_CONVERSION_FACTOR + ") > " + PURGE_TIME + ")");

        processPurge(usernames);
        mcMMO.p.getLogger().info("Purged " + usernames.size() + " users from the database.");;
    }

    public boolean removeUser(String playerName) {
        boolean success = update("DELETE FROM u, e, h, s, c " +
                "USING " + tablePrefix + "users u " +
                "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                "WHERE u.user = '" + playerName + "'") != 0;

        Misc.profileCleanup(playerName);

        return success;
    }

    public void saveUser(PlayerProfile profile) {
        int userId = readId(profile.getPlayerName());
        MobHealthbarType mobHealthbarType = profile.getMobHealthbarType();
        HudType hudType = profile.getHudType();

        saveLogin(userId, ((int) (System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR)));
        saveHuds(userId, (hudType == null ? "STANDARD" : hudType.toString()), (mobHealthbarType == null ? Config.getInstance().getMobHealthbarDefault().toString() : mobHealthbarType.toString()));
        saveLongs(
                "UPDATE " + tablePrefix + "cooldowns SET "
                    + "  mining = ?, woodcutting = ?, unarmed = ?"
                    + ", herbalism = ?, excavation = ?, swords = ?"
                    + ", axes = ?, blast_mining = ? WHERE user_id = ?",
                userId,
                profile.getSkillDATS(AbilityType.SUPER_BREAKER),
                profile.getSkillDATS(AbilityType.TREE_FELLER),
                profile.getSkillDATS(AbilityType.BERSERK),
                profile.getSkillDATS(AbilityType.GREEN_TERRA),
                profile.getSkillDATS(AbilityType.GIGA_DRILL_BREAKER),
                profile.getSkillDATS(AbilityType.SERRATED_STRIKES),
                profile.getSkillDATS(AbilityType.SKULL_SPLITTER),
                profile.getSkillDATS(AbilityType.BLAST_MINING));
        saveIntegers(
                "UPDATE " + tablePrefix + "skills SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ? WHERE user_id = ?",
                profile.getSkillLevel(SkillType.TAMING),
                profile.getSkillLevel(SkillType.MINING),
                profile.getSkillLevel(SkillType.REPAIR),
                profile.getSkillLevel(SkillType.WOODCUTTING),
                profile.getSkillLevel(SkillType.UNARMED),
                profile.getSkillLevel(SkillType.HERBALISM),
                profile.getSkillLevel(SkillType.EXCAVATION),
                profile.getSkillLevel(SkillType.ARCHERY),
                profile.getSkillLevel(SkillType.SWORDS),
                profile.getSkillLevel(SkillType.AXES),
                profile.getSkillLevel(SkillType.ACROBATICS),
                profile.getSkillLevel(SkillType.FISHING),
                userId);
        saveIntegers(
                "UPDATE " + tablePrefix + "experience SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ? WHERE user_id = ?",
                profile.getSkillXpLevel(SkillType.TAMING),
                profile.getSkillXpLevel(SkillType.MINING),
                profile.getSkillXpLevel(SkillType.REPAIR),
                profile.getSkillXpLevel(SkillType.WOODCUTTING),
                profile.getSkillXpLevel(SkillType.UNARMED),
                profile.getSkillXpLevel(SkillType.HERBALISM),
                profile.getSkillXpLevel(SkillType.EXCAVATION),
                profile.getSkillXpLevel(SkillType.ARCHERY),
                profile.getSkillXpLevel(SkillType.SWORDS),
                profile.getSkillXpLevel(SkillType.AXES),
                profile.getSkillXpLevel(SkillType.ACROBATICS),
                profile.getSkillXpLevel(SkillType.FISHING),
                userId);
    }

    public List<PlayerStat> readLeaderboard(String skillName, int pageNumber, int statsPerPage) {
        List<PlayerStat> stats = new ArrayList<PlayerStat>();

        if (checkConnected()) {
            String query = skillName.equalsIgnoreCase("ALL") ? "taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing" : skillName;
            ResultSet resultSet = null;
            PreparedStatement statement = null;

            try {
                statement = connection.prepareStatement("SELECT " + query + ", user, NOW() FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON (user_id = id) WHERE " + query + " > 0 ORDER BY " + query + " DESC, user LIMIT ?, ?");
                statement.setInt(1, (pageNumber * statsPerPage) - statsPerPage);
                statement.setInt(2, statsPerPage);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    ArrayList<String> column = new ArrayList<String>();

                    for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                        column.add(resultSet.getString(i));
                    }

                    stats.add(new PlayerStat(column.get(1), Integer.valueOf(column.get(0))));
                }
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                if (statement != null) {
                    try {
                        statement.close();
                    }
                    catch (SQLException e) {
                        // Ignore
                    }
                }
            }
        }

        return stats;
    }

    public Map<String, Integer> readRank(String playerName) {
        Map<String, Integer> skills = new HashMap<String, Integer>();

        if (checkConnected()) {
            ResultSet resultSet;

            try {
                for (SkillType skillType : SkillType.nonChildSkills()) {
                    String skillName = skillType.name().toLowerCase();
                    String sql = "SELECT COUNT(*) AS rank FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                                 "AND " + skillName + " > (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                                 "WHERE user = ?)";

                    PreparedStatement statement = connection.prepareStatement(sql);
                    statement.setString(1, playerName);
                    resultSet = statement.executeQuery();

                    resultSet.next();

                    int rank = resultSet.getInt("rank");

                    sql = "SELECT user, " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                          "AND " + skillName + " = (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                          "WHERE user = '" + playerName + "') ORDER BY user";

                    statement.close();

                    statement = connection.prepareStatement(sql);
                    resultSet = statement.executeQuery();

                    while (resultSet.next()) {
                        if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                            skills.put(skillType.name(), rank + resultSet.getRow());
                            break;
                        }
                    }

                    statement.close();
                }

                String sql = "SELECT COUNT(*) AS rank FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing > 0 " +
                        "AND taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing > " +
                        "(SELECT taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing " +
                        "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?)";

                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, playerName);
                resultSet = statement.executeQuery();

                resultSet.next();

                int rank = resultSet.getInt("rank");

                statement.close();

                sql = "SELECT user, taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing " +
                        "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing > 0 " +
                        "AND taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing = " +
                        "(SELECT taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing " +
                        "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?) ORDER BY user";

                statement = connection.prepareStatement(sql);
                statement.setString(1, playerName);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                        skills.put("ALL", rank + resultSet.getRow());
                        break;
                    }
                }

                statement.close();
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
        }

        return skills;
    }

    public void newUser(String playerName) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("INSERT INTO " + tablePrefix + "users (user, lastlogin) VALUES (?, ?)");
            statement.setString(1, playerName);
            statement.setLong(2, System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR);
            statement.execute();

            writeMissingRows(readId(playerName));
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    public List<String> loadPlayerData(String playerName) {
        List<String> playerData = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(
                    "SELECT "
                    + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, "
                    + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, "
                    + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, "
                    + "h.hudtype, h.mobhealthbar "
                    + "FROM " + tablePrefix + "users u "
                    + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                    + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                    + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                    + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                    + "WHERE u.user = ?");
            statement.setString(1, playerName);

            playerData = readRow(statement);

            if (playerData == null || playerData.size() == 0) {
                int userId = readId(playerName);

                // Check if user doesn't exist
                if (userId == 0) {
                    return playerData;
                }

                // Write missing table rows
                writeMissingRows(userId);

                // Re-read data
                playerData = loadPlayerData(playerName);
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
        return playerData;
    }

    public boolean convert(String[] data) throws Exception {
        String playerName = data[0];

        // Check for things we don't want put in the DB
        if (playerName == null || playerName.equalsIgnoreCase("null") || playerName.length() > 16) {
            return false;
        }

        String mining = (data.length > 1) ? data[1] : null;
        String woodcutting = (data.length > 5) ? data[5] : null;
        String repair = (data.length > 7) ? data[7] : null;
        String unarmed = (data.length > 8) ? data[8] : null;
        String herbalism = (data.length > 9) ? data[9] : null;
        String excavation = (data.length > 10) ? data[10] : null;
        String archery = (data.length > 11) ? data[11] : null;
        String swords = (data.length > 12) ? data[12] : null;
        String axes = (data.length > 13) ? data[13] : null;
        String acrobatics = (data.length > 14) ? data[14] : null;
        String taming = (data.length > 24) ? data[24] : null;
        String fishing = (data.length > 34) ? data[34] : null;

        String miningXP = (data.length > 4) ? data[4] : null;
        String woodCuttingXP = (data.length > 6) ? data[6] : null;;
        String repairXP = (data.length > 15) ? data[15] : null;
        String unarmedXP = (data.length > 16) ? data[16] : null;
        String herbalismXP = (data.length > 17) ? data[17] : null;
        String excavationXP = (data.length > 18) ? data[18] : null;
        String archeryXP = (data.length > 19) ? data[19] : null;
        String swordsXP = (data.length > 20) ? data[20] : null;
        String axesXP = (data.length > 21) ? data[21] : null;
        String acrobaticsXP = (data.length > 22) ? data[22] : null;
        String tamingXP = (data.length > 25) ? data[25] : null;
        String fishingXP = (data.length > 35) ? data[35] : null;

        String superBreakerCooldown = (data.length > 32) ? data[32] : null;
        String treeFellerCooldown = (data.length > 28) ? data[28] : null;
        String berserkCooldown = (data.length > 26) ? data[26] : null;
        String greenTerraCooldown = (data.length > 29) ? data[29] : null;
        String gigaDrillBreakerCooldown = (data.length > 27) ? data[27] : null;
        String serratedStrikesCooldown = (data.length > 30) ? data[30] : null;
        String skullSplitterCooldown = (data.length > 31) ? data[31] : null;
        String blastMiningCooldown = (data.length > 36) ? data[36] : null;

        String hudType = (data.length > 33) ? data[33] : null;
        String mobHealthbarType = (data.length > 38 ? data[38] : null);
        long lastLogin = mcMMO.p.getServer().getOfflinePlayer(playerName).getLastPlayed();

        int id = readId(playerName); // Check to see if the user is in the DB

        // Create the user if they don't exist
        if (id == 0) {
            newUser(playerName);
            id = readId(playerName);
        }

        saveLogin(id, lastLogin);
        saveIntegers(
                "UPDATE " + tablePrefix + "skills SET "
                        + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                        + ", unarmed = ?, herbalism = ?, excavation = ?"
                        + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                        + ", fishing = ? WHERE user_id = ?",
                StringUtils.getInt(taming), StringUtils.getInt(mining),
                StringUtils.getInt(repair), StringUtils.getInt(woodcutting),
                StringUtils.getInt(unarmed), StringUtils.getInt(herbalism),
                StringUtils.getInt(excavation), StringUtils.getInt(archery),
                StringUtils.getInt(swords), StringUtils.getInt(axes),
                StringUtils.getInt(acrobatics), StringUtils.getInt(fishing),
                id);
        saveIntegers(
                "UPDATE " + tablePrefix + "experience SET "
                        + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                        + ", unarmed = ?, herbalism = ?, excavation = ?"
                        + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                        + ", fishing = ? WHERE user_id = ?",
                StringUtils.getInt(tamingXP), StringUtils.getInt(miningXP),
                StringUtils.getInt(repairXP), StringUtils.getInt(woodCuttingXP),
                StringUtils.getInt(unarmedXP), StringUtils.getInt(herbalismXP),
                StringUtils.getInt(excavationXP), StringUtils.getInt(archeryXP),
                StringUtils.getInt(swordsXP), StringUtils.getInt(axesXP),
                StringUtils.getInt(acrobaticsXP), StringUtils.getInt(fishingXP),
                id);
        saveLongs(
                "UPDATE " + tablePrefix + "cooldowns SET "
                        + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                        + ", unarmed = ?, herbalism = ?, excavation = ?"
                        + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                        + ", blast_mining = ? WHERE user_id = ?",
                id,
                StringUtils.getLong(null), StringUtils.getLong(superBreakerCooldown),
                StringUtils.getLong(null), StringUtils.getInt(treeFellerCooldown),
                StringUtils.getLong(berserkCooldown), StringUtils.getLong(greenTerraCooldown),
                StringUtils.getLong(gigaDrillBreakerCooldown), StringUtils.getLong(null),
                StringUtils.getLong(serratedStrikesCooldown), StringUtils.getLong(skullSplitterCooldown),
                StringUtils.getLong(null), StringUtils.getLong(blastMiningCooldown));
        saveHuds(id, hudType, mobHealthbarType);
        return true;
    }

    /**
    * Check connection status and re-establish if dead or stale.
    *
    * If the very first immediate attempt fails, further attempts
    * will be made in progressively larger intervals up to MAX_WAIT
    * intervals.
    *
    * This allows for MySQL to time out idle connections as needed by
    * server operator, without affecting McMMO, while still providing
    * protection against a database outage taking down Bukkit's tick
    * processing loop due to attempting a database connection each
    * time McMMO needs the database.
    *
    * @return the boolean value for whether or not we are connected
    */
    public boolean checkConnected() {
        boolean isClosed = true;
        boolean isValid = false;
        boolean exists = (connection != null);

        // If we're waiting for server to recover then leave early
        if (nextReconnectTimestamp > 0 && nextReconnectTimestamp > System.nanoTime()) {
            return false;
        }

        if (exists) {
            try {
                isClosed = connection.isClosed();
            }
            catch (SQLException e) {
                isClosed = true;
                e.printStackTrace();
                printErrors(e);
            }

            if (!isClosed) {
                try {
                    isValid = connection.isValid(VALID_TIMEOUT);
                }
                catch (SQLException e) {
                    // Don't print stack trace because it's valid to lose idle connections to the server and have to restart them.
                    isValid = false;
                }
            }
        }

        // Leave if all ok
        if (exists && !isClosed && isValid) {
            // Housekeeping
            nextReconnectTimestamp = 0;
            reconnectAttempt = 0;
            return true;
        }

        // Cleanup after ourselves for GC and MySQL's sake
        if (exists && !isClosed) {
            try {
                connection.close();
            }
            catch (SQLException ex) {
                // This is a housekeeping exercise, ignore errors
            }
        }

        // Try to connect again
        connect();

        // Leave if connection is good
        try {
            if (connection != null && !connection.isClosed()) {
                // Schedule a database save if we really had an outage
                if (reconnectAttempt > 1) {
                    new SQLReconnectTask().runTaskLater(mcMMO.p, 5);
                }
                nextReconnectTimestamp = 0;
                reconnectAttempt = 0;
                return true;
            }
        }
        catch (SQLException e) {
            // Failed to check isClosed, so presume connection is bad and attempt later
            e.printStackTrace();
            printErrors(e);
        }

        reconnectAttempt++;
        nextReconnectTimestamp = (long) (System.nanoTime() + Math.min(MAX_WAIT, (reconnectAttempt * SCALING_FACTOR * MIN_WAIT)));
        return false;
    }

    /**
    * Attempt to connect to the mySQL database.
    */
    private void connect() {
        connectionString = "jdbc:mysql://" + Config.getInstance().getMySQLServerName() + ":" + Config.getInstance().getMySQLServerPort() + "/" + Config.getInstance().getMySQLDatabaseName();

        try {
            mcMMO.p.getLogger().info("Attempting connection to MySQL...");

            // Force driver to load if not yet loaded
            Class.forName("com.mysql.jdbc.Driver");
            Properties connectionProperties = new Properties();
            connectionProperties.put("user", Config.getInstance().getMySQLUserName());
            connectionProperties.put("password", Config.getInstance().getMySQLUserPassword());
            connectionProperties.put("autoReconnect", "false");
            connectionProperties.put("maxReconnects", "0");
            connection = DriverManager.getConnection(connectionString, connectionProperties);

            mcMMO.p.getLogger().info("Connection to MySQL was a success!");
        }
        catch (SQLException ex) {
            connection = null;

            if (reconnectAttempt == 0 || reconnectAttempt >= 11) {
                mcMMO.p.getLogger().info("Connection to MySQL failed!");
            }
        }
        catch (ClassNotFoundException ex) {
            connection = null;

            if (reconnectAttempt == 0 || reconnectAttempt >= 11) {
                mcMMO.p.getLogger().info("MySQL database driver not found!");
            }
        }
    }

    /**
    * Attempt to create the database structure.
    */
    private void createStructure() {
        write("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "users` ("
                + "`id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                + "`user` varchar(40) NOT NULL,"
                + "`lastlogin` int(32) unsigned NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `user` (`user`)) DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
        write("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "huds` ("
                + "`user_id` int(10) unsigned NOT NULL,"
                + "`hudtype` varchar(50) NOT NULL DEFAULT 'STANDARD',"
                + "`mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "',"
                + "PRIMARY KEY (`user_id`)) "
                + "DEFAULT CHARSET=latin1;");
        write("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "cooldowns` ("
                + "`user_id` int(10) unsigned NOT NULL,"
                + "`taming` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`mining` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`woodcutting` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`repair` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`unarmed` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`herbalism` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`excavation` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`archery` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`swords` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`axes` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`acrobatics` int(32) unsigned NOT NULL DEFAULT '0',"
                + "`blast_mining` int(32) unsigned NOT NULL DEFAULT '0',"
                + "PRIMARY KEY (`user_id`)) "
                + "DEFAULT CHARSET=latin1;");
        write("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "skills` ("
                + "`user_id` int(10) unsigned NOT NULL,"
                + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                + "PRIMARY KEY (`user_id`)) "
                + "DEFAULT CHARSET=latin1;");
        write("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "experience` ("
                + "`user_id` int(10) unsigned NOT NULL,"
                + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                + "PRIMARY KEY (`user_id`)) "
                + "DEFAULT CHARSET=latin1;");

        for (DatabaseUpdateType updateType : DatabaseUpdateType.values()) {
            checkDatabaseStructure(updateType);
        }
    }

    /**
    * Check database structure for missing values.
    *
    * @param update Type of data to check updates for
    */
    private void checkDatabaseStructure(DatabaseUpdateType update) {
        String sql = "";

        switch (update) {
            case BLAST_MINING:
                sql = "SELECT * FROM `" + tablePrefix + "cooldowns` ORDER BY `" + tablePrefix + "cooldowns`.`blast_mining` ASC LIMIT 0 , 30";
                break;

            case FISHING:
                sql = "SELECT * FROM `" + tablePrefix + "experience` ORDER BY `" + tablePrefix + "experience`.`fishing` ASC LIMIT 0 , 30";
                break;

            case INDEX:
                if (read("SHOW INDEX FROM " + tablePrefix + "skills").size() != 13 && checkConnected()) {
                    mcMMO.p.getLogger().info("Indexing tables, this may take a while on larger databases");
                    write("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_taming` (`taming`) USING BTREE, "
                            + "ADD INDEX `idx_mining` (`mining`) USING BTREE, "
                            + "ADD INDEX `idx_woodcutting` (`woodcutting`) USING BTREE, "
                            + "ADD INDEX `idx_repair` (`repair`) USING BTREE, "
                            + "ADD INDEX `idx_unarmed` (`unarmed`) USING BTREE, "
                            + "ADD INDEX `idx_herbalism` (`herbalism`) USING BTREE, "
                            + "ADD INDEX `idx_excavation` (`excavation`) USING BTREE, "
                            + "ADD INDEX `idx_archery` (`archery`) USING BTREE, "
                            + "ADD INDEX `idx_swords` (`swords`) USING BTREE, "
                            + "ADD INDEX `idx_axes` (`axes`) USING BTREE, "
                            + "ADD INDEX `idx_acrobatics` (`acrobatics`) USING BTREE, "
                            + "ADD INDEX `idx_fishing` (`fishing`) USING BTREE;");
                }
                return;

            case MOB_HEALTHBARS:
                sql = "SELECT * FROM `" + tablePrefix + "huds` ORDER BY `" + tablePrefix + "huds`.`mobhealthbar` ASC LIMIT 0 , 30";
                break;

            case PARTY_NAMES:
                write("ALTER TABLE `" + tablePrefix + "users` DROP COLUMN `party` ;");
                return;

            case KILL_ORPHANS:
                mcMMO.p.getLogger().info("Killing orphans");
                write(
                        "DELETE FROM " + tablePrefix + "experience " +
                         "WHERE NOT EXISTS (SELECT * FROM " +
                         tablePrefix + "users u WHERE " +
                         tablePrefix + "experience.user_id = u.id);"
                         );
                write(
                        "DELETE FROM " + tablePrefix + "huds " +
                         "WHERE NOT EXISTS (SELECT * FROM " +
                         tablePrefix + "users u WHERE " +
                         tablePrefix + "huds.user_id = u.id);"
                         );
                write(
                        "DELETE FROM " + tablePrefix + "cooldowns " +
                         "WHERE NOT EXISTS (SELECT * FROM " +
                         tablePrefix + "users u WHERE " +
                         tablePrefix + "cooldowns.user_id = u.id);"
                         );
                write(
                        "DELETE FROM " + tablePrefix + "skills " +
                         "WHERE NOT EXISTS (SELECT * FROM " +
                         tablePrefix + "users u WHERE " +
                         tablePrefix + "skills.user_id = u.id);"
                         );
                return;

            default:
                break;
        }

        ResultSet resultSet = null;
        HashMap<Integer, ArrayList<String>> rows = new HashMap<Integer, ArrayList<String>>();
        PreparedStatement statement = null;

        try {
            if (!checkConnected()) {
                return;
            }

            statement = connection.prepareStatement(sql);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                ArrayList<String> column = new ArrayList<String>();

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    column.add(resultSet.getString(i));
                }

                rows.put(resultSet.getRow(), column);
            }
        }
        catch (SQLException ex) {
            switch (update) {
                case BLAST_MINING:
                    mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Blast Mining...");
                    write("ALTER TABLE `"+tablePrefix + "cooldowns` ADD `blast_mining` int(32) NOT NULL DEFAULT '0' ;");
                    break;

                case FISHING:
                    mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Fishing...");
                    write("ALTER TABLE `"+tablePrefix + "skills` ADD `fishing` int(10) NOT NULL DEFAULT '0' ;");
                    write("ALTER TABLE `"+tablePrefix + "experience` ADD `fishing` int(10) NOT NULL DEFAULT '0' ;");
                    break;

                case MOB_HEALTHBARS:
                    mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for mob healthbars...");
                    write("ALTER TABLE `" + tablePrefix + "huds` ADD `mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "' ;");
                    break;

                default:
                    break;
            }
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore the error, we're leaving
                }
            }
        }
    }

    /**
    * Attempt to write the SQL query.
    *
    * @param sql Query to write.
    * @return true if the query was successfully written, false otherwise.
    */
    private boolean write(String sql) {
        if (!checkConnected()) {
            return false;
        }

        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            statement.executeUpdate();
            return true;
        }
        catch (SQLException ex) {
            if (!sql.equalsIgnoreCase("ALTER TABLE `" + tablePrefix + "users` DROP COLUMN `party` ;")) {
                printErrors(ex);
            }
            return false;
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    /**
    * Returns the number of rows affected by either a DELETE or UPDATE query
    *
    * @param sql SQL query to execute
    * @return the number of rows affected
    */
    private int update(String sql) {
        int rows = 0;

        if (checkConnected()) {
            PreparedStatement statement = null;

            try {
                statement = connection.prepareStatement(sql);
                rows = statement.executeUpdate();
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                if (statement != null) {
                    try {
                        statement.close();
                    }
                    catch (SQLException e) {
                        // Ignore
                    }
                }
            }
        }

        return rows;
    }

    /**
    * Read SQL query.
    *
    * @param sql SQL query to read
    * @return the rows in this SQL query
    */
    private HashMap<Integer, ArrayList<String>> read(String sql) {
        HashMap<Integer, ArrayList<String>> rows = new HashMap<Integer, ArrayList<String>>();

        if (checkConnected()) {
            PreparedStatement statement = null;
            ResultSet resultSet;

            try {
                statement = connection.prepareStatement(sql);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    ArrayList<String> column = new ArrayList<String>();

                    for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                        column.add(resultSet.getString(i));
                    }

                    rows.put(resultSet.getRow(), column);
                }
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                if (statement != null) {
                    try {
                        statement.close();
                    }
                    catch (SQLException e) {
                        // Ignore
                    }
                }
            }
        }

        return rows;
    }

    private ArrayList<String> readRow(PreparedStatement statement) {
        ArrayList<String> playerData = new ArrayList<String>();

        if (checkConnected()) {
            ResultSet resultSet = null;

            try {
                resultSet = statement.executeQuery();

                if (resultSet.next()) {
                    for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                        playerData.add(resultSet.getString(i));
                    }
                }
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                if (statement != null) {
                    try {
                        statement.close();
                    }
                    catch (SQLException e) {
                        // Ignore
                    }
                }
            }
        }

        return playerData;
    }

    /**
    * Get the Integer. Only return first row / first field.
    *
    * @param sql SQL query to execute
    * @return the value in the first row / first field
    */
    private int readInt(PreparedStatement statement) {
        int result = 0;

        if (checkConnected()) {
            ResultSet resultSet = null;

            try {
                resultSet = statement.executeQuery();

                if (resultSet.next()) {
                    result = resultSet.getInt(1);
                }
            }
            catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                if (statement != null) {
                    try {
                        statement.close();
                    }
                    catch (SQLException e) {
                        // Ignore
                    }
                }
            }
        }

        return result;
    }

    private void writeMissingRows(int id) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "experience (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "skills (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "cooldowns (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "huds (user_id, mobhealthbar) VALUES (? ,'" + Config.getInstance().getMobHealthbarDefault().name() + "')");
            statement.setInt(1, id);
            statement.execute();
            statement.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    private void processPurge(Collection<ArrayList<String>> usernames) {
        for (ArrayList<String> user : usernames) {
            Misc.profileCleanup(user.get(0));
        }
    }

    private void saveIntegers(String sql, int... args) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(sql);
            int i = 1;

            for (int arg : args) {
                statement.setInt(i++, arg);
            }

            statement.execute();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    private void saveLongs(String sql, int id, long... args) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(sql);
            int i = 1;

            for (long arg : args) {
                statement.setLong(i++, arg);
            }

            statement.setInt(i++, id);
            statement.execute();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    private int readId(String playerName) {
        int id = 0;

        try {
            PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + tablePrefix + "users WHERE user = ?");
            statement.setString(1, playerName);
            id = readInt(statement);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }

        return id;
    }

    private void saveLogin(int id, long login) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET lastlogin = ? WHERE id = ?");
            statement.setLong(1, login);
            statement.setInt(2, id);
            statement.execute();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    private void saveHuds(int userId, String hudType, String mobHealthBar) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET hudtype = ?, mobhealthbar = ? WHERE user_id = ?");
            statement.setString(1, hudType);
            statement.setString(2, mobHealthBar);
            statement.setInt(3, userId);
            statement.execute();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            if (statement != null) {
                try {
                    statement.close();
                }
                catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    private void printErrors(SQLException ex) {
        mcMMO.p.getLogger().severe("SQLException: " + ex.getMessage());
        mcMMO.p.getLogger().severe("SQLState: " + ex.getSQLState());
        mcMMO.p.getLogger().severe("VendorError: " + ex.getErrorCode());
    }
}