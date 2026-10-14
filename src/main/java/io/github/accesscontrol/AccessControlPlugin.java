package io.github.accesscontrol;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.RegisterEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** AccessControl: a tiny username allow-list for Paper (offline mode, AuthMeReloaded). */
public final class AccessControlPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String PENDING_MARKER = " # pending";
    private static final String DENY_MESSAGE = "You are not allowed on this server.";
    private static final String KICK_MESSAGE = "You have been removed from the server access list.";
    /** Safety-net pending check interval: 10 minutes in ticks. */
    private static final long PENDING_CHECK_INTERVAL_TICKS = 10L * 60L * 20L;
    private static final int MAX_LOG_ENTRIES = 50;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Object lock = new Object();
    private final Set<String> allowed = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    private final Deque<String> deniedLog = new ArrayDeque<>();

    private Path allowedFile;
    private Path logFile;
    private long allowedFileModified;
    private BukkitTask pendingCheckTask;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create the data folder");
        }
        allowedFile = getDataFolder().toPath().resolve("allowed.txt");
        logFile = getDataFolder().toPath().resolve("logs.txt");

        reloadAllowed();
        loadDeniedLog();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand accessCommand = getCommand("access");
        if (accessCommand != null) {
            accessCommand.setExecutor(this);
            accessCommand.setTabCompleter(this);
        }

        int allowedCount;
        int pendingCount;
        synchronized (lock) {
            if (!pending.isEmpty()) {
                schedulePendingCheck();
            }
            allowedCount = allowed.size();
            pendingCount = pending.size();
        }
        getLogger().info("Allow-list loaded: " + allowedCount + " allowed, " + pendingCount + " pending."
                + (isAuthMeEnabled() ? "" : " AuthMe was not found; new adds will be pending."));
    }

    @Override
    public void onDisable() {
        cancelPendingCheckIfIdle();
    }

    // Join handling. AsyncPlayerPreLoginEvent is async; shared state is only
    // touched under {@code lock} and no main-thread-only Bukkit API is called.

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        refreshAllowedIfChanged();

        String name = event.getName().toLowerCase(Locale.ROOT);
        boolean listed;
        synchronized (lock) {
            listed = allowed.contains(name) || pending.contains(name);
        }

        if (listed) {
            event.allow();
            return;
        }

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(DENY_MESSAGE));
        recordDenial(name, event.getAddress().getHostAddress());
    }

    private void recordDenial(String name, String ip) {
        String line = LOG_FORMAT.format(LocalDateTime.now()) + " | " + name + " | " + ip;
        synchronized (lock) {
            deniedLog.addLast(line);
            while (deniedLog.size() > MAX_LOG_ENTRIES) {
                deniedLog.removeFirst();
            }
            writeDeniedLog();
        }
    }

    // Commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        refreshAllowedIfChanged();

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length != 2) {
                    sendUsage(sender);
                    return true;
                }
                handleAdd(sender, args[1]);
            }
            case "remove" -> {
                if (args.length != 2) {
                    sendUsage(sender);
                    return true;
                }
                handleRemove(sender, args[1]);
            }
            case "list" -> handleList(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("add", "remove", "list")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            synchronized (lock) {
                return Stream.concat(allowed.stream(), pending.stream())
                        .filter(n -> n.startsWith(prefix))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /access add <name> | /access remove <name> | /access list"));
    }

    private void handleAdd(CommandSender sender, String rawName) {
        if (!NAME_PATTERN.matcher(rawName).matches()) {
            sender.sendMessage(Component.text("Invalid username: 3-16 characters, letters, digits and underscore only."));
            return;
        }

        String name = rawName.toLowerCase(Locale.ROOT);
        boolean registered = isRegisteredInAuthMe(name);

        synchronized (lock) {
            if (allowed.contains(name) || pending.contains(name)) {
                sender.sendMessage(Component.text(name + " is already on the list."));
                return;
            }
            if (registered) {
                allowed.add(name);
            } else {
                pending.add(name);
            }
            writeAllowed();
        }

        if (registered) {
            sender.sendMessage(Component.text("Added " + name + " (registered in AuthMe)."));
        } else {
            sender.sendMessage(Component.text("Added " + name + ", waiting for " + name + " to register in AuthMe."));
            schedulePendingCheck();
        }
    }

    private void handleRemove(CommandSender sender, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);

        boolean removed;
        synchronized (lock) {
            removed = allowed.remove(name) | pending.remove(name);
            if (removed) {
                writeAllowed();
            }
        }

        if (!removed) {
            sender.sendMessage(Component.text(name + " is not on the list."));
            return;
        }

        sender.sendMessage(Component.text("Removed " + name + "."));
        cancelPendingCheckIfIdle();
        kickIfOnline(name);
    }

    private void handleList(CommandSender sender) {
        List<String> names;
        synchronized (lock) {
            if (allowed.isEmpty() && pending.isEmpty()) {
                sender.sendMessage(Component.text("The allow-list is empty."));
                return;
            }
            names = new ArrayList<>();
            allowed.stream().sorted().forEach(n -> names.add(n));
            pending.stream().sorted().forEach(n -> names.add(n + " (pending)"));
        }
        sender.sendMessage(Component.text("Allowed players (" + names.size() + "): " + String.join(", ", names)));
    }

    private void kickIfOnline(String name) {
        if (Bukkit.getPlayerExact(name) == null) {
            return;
        }
        // Kicking must run on the main thread; the task is scheduled explicitly
        // so the call stays correct from any caller.
        Bukkit.getScheduler().runTask(this, () -> {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null && online.isOnline()) {
                online.kick(Component.text(KICK_MESSAGE));
            }
        });
    }

    // Pending confirmation. RegisterEvent fires on the main thread (AuthMe's
    // CustomEvent base defaults to a synchronous event), but notifyOps guards
    // with Bukkit.isPrimaryThread() anyway.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(RegisterEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            confirmPending(player.getName());
        }
    }

    private void confirmPending(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);

        boolean confirmed = false;
        synchronized (lock) {
            if (pending.remove(name)) {
                allowed.add(name);
                writeAllowed();
                confirmed = true;
            }
        }

        if (confirmed) {
            getLogger().info(name + " registered in AuthMe, access confirmed");
            cancelPendingCheckIfIdle();
            notifyOps(name);
        }
    }

    private void notifyOps(String name) {
        Runnable notify = () -> {
            String message = name + " registered in AuthMe, access confirmed";
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("accesscontrol.admin")) {
                    online.sendMessage(Component.text(message));
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            notify.run();
        } else {
            Bukkit.getScheduler().runTask(this, notify);
        }
    }

    private void schedulePendingCheck() {
        synchronized (lock) {
            if (pendingCheckTask != null || pending.isEmpty()) {
                return;
            }
            pendingCheckTask = getServer().getScheduler().runTaskTimerAsynchronously(
                    this, this::runPendingCheck, PENDING_CHECK_INTERVAL_TICKS, PENDING_CHECK_INTERVAL_TICKS);
        }
    }

    private void runPendingCheck() {
        List<String> toCheck;
        synchronized (lock) {
            if (pending.isEmpty()) {
                cancelPendingCheckLocked();
                return;
            }
            toCheck = List.copyOf(pending);
        }

        if (!isAuthMeEnabled()) {
            return;
        }

        List<String> nowRegistered = new ArrayList<>();
        for (String name : toCheck) {
            if (isRegisteredInAuthMe(name)) {
                nowRegistered.add(name);
            }
        }
        for (String name : nowRegistered) {
            confirmPending(name);
        }
    }

    private void cancelPendingCheckIfIdle() {
        synchronized (lock) {
            cancelPendingCheckLocked();
        }
    }

    /** Caller must hold {@code lock}. BukkitTask#cancel is thread-safe. */
    private void cancelPendingCheckLocked() {
        if (pendingCheckTask != null) {
            BukkitTask task = pendingCheckTask;
            pendingCheckTask = null;
            task.cancel();
        }
    }

    // AuthMe access

    private boolean isAuthMeEnabled() {
        Plugin authMe = getServer().getPluginManager().getPlugin("AuthMe");
        return authMe != null && authMe.isEnabled();
    }

    /**
     * Returns whether the name is registered in AuthMe, or false if AuthMe is
     * unavailable, so callers treat it as pending. AuthMeApi#isRegistered reads
     * the DataSource, which AuthMe itself calls from async tasks.
     */
    private boolean isRegisteredInAuthMe(String name) {
        if (!isAuthMeEnabled()) {
            return false;
        }
        try {
            AuthMeApi api = AuthMeApi.getInstance();
            return api != null && api.isRegistered(name);
        } catch (Throwable t) {
            getLogger().warning("AuthMe check failed for " + name + ": " + t.getMessage());
            return false;
        }
    }

    // File state

    /** Picks up hand edits by comparing the file's last-modified time. */
    private void refreshAllowedIfChanged() {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(allowedFile).toMillis();
        } catch (IOException e) {
            return;
        }
        synchronized (lock) {
            if (mtime != allowedFileModified) {
                reloadAllowedLocked();
            }
        }
    }

    private void reloadAllowed() {
        synchronized (lock) {
            reloadAllowedLocked();
        }
    }

    /** Caller must hold {@code lock}. */
    private void reloadAllowedLocked() {
        allowed.clear();
        pending.clear();
        try {
            if (Files.exists(allowedFile)) {
                for (String raw : Files.readAllLines(allowedFile, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    boolean isPending = false;
                    int marker = line.indexOf('#');
                    if (marker >= 0) {
                        isPending = line.substring(marker + 1).trim().equalsIgnoreCase("pending");
                        line = line.substring(0, marker).trim();
                    }
                    if (!line.isEmpty()) {
                        (isPending ? pending : allowed).add(line.toLowerCase(Locale.ROOT));
                    }
                }
                allowedFileModified = Files.getLastModifiedTime(allowedFile).toMillis();
            } else {
                allowedFileModified = 0;
            }
        } catch (IOException e) {
            getLogger().warning("Could not read allowed.txt: " + e.getMessage());
        }
    }

    /** Caller must hold {@code lock}. */
    private void writeAllowed() {
        List<String> lines = new ArrayList<>();
        lines.add("# AccessControl allow-list. One username per line, case-insensitive.");
        lines.add("# A line ending with '# pending' is on the list but not yet registered in AuthMe.");
        for (String name : new TreeSet<>(allowed)) {
            lines.add(name);
        }
        for (String name : new TreeSet<>(pending)) {
            lines.add(name + PENDING_MARKER);
        }
        atomicWrite(allowedFile, lines);
        try {
            allowedFileModified = Files.getLastModifiedTime(allowedFile).toMillis();
        } catch (IOException e) {
            getLogger().warning("Could not stat allowed.txt: " + e.getMessage());
        }
    }

    private void loadDeniedLog() {
        synchronized (lock) {
            deniedLog.clear();
            try {
                if (Files.exists(logFile)) {
                    for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                        if (!line.isEmpty()) {
                            deniedLog.addLast(line);
                        }
                    }
                    while (deniedLog.size() > MAX_LOG_ENTRIES) {
                        deniedLog.removeFirst();
                    }
                }
            } catch (IOException e) {
                getLogger().warning("Could not read logs.txt: " + e.getMessage());
            }
        }
    }

    /** Caller must hold {@code lock}. */
    private void writeDeniedLog() {
        atomicWrite(logFile, new ArrayList<>(deniedLog));
    }

    /** Writes {@code lines} atomically: temp file first, then move over {@code target}. */
    private void atomicWrite(Path target, List<String> lines) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().warning("Could not write " + target.getFileName() + ": " + e.getMessage());
        }
    }
}
