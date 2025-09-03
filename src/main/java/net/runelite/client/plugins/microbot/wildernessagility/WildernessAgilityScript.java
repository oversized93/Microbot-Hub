package net.runelite.client.plugins.microbot.wildernessagility;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.TileObject;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.*;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.*;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import static net.runelite.api.Skill.AGILITY;
import lombok.Getter;

/**
 * Wilderness Agility Script for RuneLite
 */
public final class WildernessAgilityScript extends Script {
    public static final String VERSION = "1.5.1";

    // --- Constants ---
    private static final int ACTION_DELAY = 3000;
    private static final int XP_TIMEOUT = 8000;
    private static final int DISPENSER_ID = 53224;
    private static final int TICKET_ITEM_ID = 29460;
    private static final int FOOD_PRIMARY = 24592;
    private static final int FOOD_SECONDARY = 24595;
    private static final int FOOD_TERTIARY = 24589;
    private static final int FOOD_DROP = 24598;
    private static final int KNIFE_ID = 962;
    private static final int TELEPORT_ID = 24963;
    private static final int COINS_ID = 995;
    private static final WorldPoint START_POINT = new WorldPoint(3004, 3936, 0);
    private static final WorldPoint DISPENSER_POINT = new WorldPoint(3004, 3936, 0);

    // --- Config & Plugin ---
    private WildernessAgilityConfig config;
    @Inject
    private WildernessAgilityPlugin plugin;

    // --- Obstacle Models ---
    private final List<WildernessAgilityObstacleModel> obstacles = List.of(
        new WildernessAgilityObstacleModel(23137, false),
        new WildernessAgilityObstacleModel(23132, true),
        new WildernessAgilityObstacleModel(23556, false),
        new WildernessAgilityObstacleModel(23542, true),
        new WildernessAgilityObstacleModel(23640, false)
    );

    // --- Dispenser Tracking ---
    private int dispenserLoots = 0;
    private boolean waitingForDispenserLoot = false;
    private int dispenserLootAttempts = 0;
    private int dispenserTicketsBefore = 0;
    private int dispenserPreValue = 0;
    private TileObject cachedDispenserObj = null;
    private long lastObjectCheck = 0;

    // --- Lap & XP Tracking ---
    public int lapCount = 0;
    private int logStartXp = 0;
    private int pipeStartXp = 0;
    private int ropeStartXp = 0;
    private int stonesStartXp = 0;
    private long previousLapTime = 0;
    private long fastestLapTime = Long.MAX_VALUE;
    private long lastLapTimestamp = 0;
    private long startTime = 0;

    // --- State & Progress ---
    private enum ObstacleState {
        INIT,
        START,
        PIPE,
        ROPE,
        STONES,
        LOG,
        ROCKS,
        DISPENSER,
        CONFIG_CHECKS,
        WORLD_HOP_1,
        WORLD_HOP_2,
        WALK_TO_LEVER,
        INTERACT_LEVER,
        BANKING,
        POST_BANK_CONFIG,
        WALK_TO_COURSE,
        SWAP_BACK,
        PIT_RECOVERY
    }
    private ObstacleState currentState = ObstacleState.START;
    private ObstacleState pitRecoveryTarget = null;
    private boolean isWaitingForPipe = false;
    private boolean isWaitingForRope = false;
    private boolean isWaitingForStones = false;
    private boolean isWaitingForLog = false;
    private boolean pipeJustCompleted = false;
    private boolean ropeRecoveryWalked = false;
    private boolean forceBankNextLoot = false;
    private boolean forceStartAtCourse = false;
    private int originalWorld = -1;
    private int bankWorld1 = -1;
    private int bankWorld2 = -1;
    private long lastLadderInteractTime = 0;
    private int cachedInventoryValue = 0;
    private long lastObstacleInteractTime = 0;
    private WorldPoint lastObstaclePosition = null;
    @Getter
    private volatile long lastFcJoinMessageTime = 0;
    private long pipeInteractionStartTime = 0;

    // --- Position-Based Timeout & Retry System ---
    private WorldPoint lastTrackedPosition = null;
    private long positionLastChangedTime = 0;
    private long lastPositionCheckTime = 0;
    private int currentStateRetryAttempts = 0;
    private boolean isInRetryMode = false;
    private static final long POSITION_CHECK_INTERVAL = 1000; // Check position every 1 second
    private static final int MAX_RETRY_ATTEMPTS = 1; // Only retry once before moving to next state

    /**
     * Starts the Wilderness Agility script.
     * @param config The script configuration
     * @return true if started successfully
     */
    public boolean run(WildernessAgilityConfig config) {
        this.config = config;
        forceStartAtCourse = false; // Always reset on run
        if (config.debugMode()) {
            currentState = ObstacleState.valueOf(config.debugStartState().name());
            Microbot.log("[DEBUG MODE] Starting in state: " + currentState);
        } else {
            currentState = ObstacleState.START;
        }
        startTime = System.currentTimeMillis();
        Microbot.log("[WildernessAgilityScript] startup called");
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (Rs2Player.getHealthPercentage() <= 0) {
                    handlePlayerDeath();
                    return;
                }
                // Pitfall detection logic
                WorldPoint currentLoc = Rs2Player.getWorldLocation();
                if (currentLoc != null && isUnderground(currentLoc)) {
                    if (isWaitingForRope) {
                        pitRecoveryTarget = ObstacleState.ROPE;
                        isWaitingForRope = false;
                    } else if (currentState.ordinal() <= ObstacleState.ROPE.ordinal()) {
                        pitRecoveryTarget = ObstacleState.ROPE;
                    } else if (isWaitingForLog) {
                        pitRecoveryTarget = ObstacleState.LOG;
                        isWaitingForLog = false;
                    } else if (currentState == ObstacleState.LOG) {
                        pitRecoveryTarget = ObstacleState.LOG;
                    }
                    currentState = ObstacleState.PIT_RECOVERY;
                }
                if (System.currentTimeMillis() - lastObjectCheck > 1000) {
                    cachedDispenserObj = getDispenserObj();
                    lastObjectCheck = System.currentTimeMillis();
                }
                
                // Position-based timeout and retry system
                handlePositionTimeoutLogic();
                
                switch (currentState) {
                    case INIT: currentState = ObstacleState.PIPE; break;
                    case START: handleStart(); break;
                    case PIPE: handlePipe(); break;
                    case ROPE: handleRope(); break;
                    case STONES: pipeJustCompleted = false; handleStones(); break;
                    case LOG: handleLog(); break;
                    case ROCKS: handleRocks(); break;
                    case DISPENSER: handleDispenser(); break;
                    case CONFIG_CHECKS: handleConfigChecks(); break;
                    case WORLD_HOP_1: handleWorldHop1(); break;
                    case WORLD_HOP_2: handleWorldHop2(); break;
                    case WALK_TO_LEVER: handleWalkToLever(); break;
                    case INTERACT_LEVER: handleInteractLever(); break;
                    case BANKING: handleBanking(); break;
                    case POST_BANK_CONFIG: handlePostBankConfig(); break;
                    case WALK_TO_COURSE: handleWalkToCourse(); break;
                    case SWAP_BACK: handleSwapBack(); break;
                    case PIT_RECOVERY: recoverFromPit(); break;
                }
                if (lastObstacleInteractTime > 0 && lastObstaclePosition != null && System.currentTimeMillis() - lastObstacleInteractTime > 2000) {
                    WorldPoint currentPos = Rs2Player.getWorldLocation();
                    if (currentPos != null && currentPos.equals(lastObstaclePosition)) {
                        switch (currentState) {
                            case ROPE: isWaitingForRope = false; break;
                            case LOG: isWaitingForLog = false; break;
                            case STONES: isWaitingForStones = false; break;
                            case PIPE: if (!pipeJustCompleted) { isWaitingForPipe = false; } break;
                        }
                        lastObstacleInteractTime = 0;
                        lastObstaclePosition = null;
                    }
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Shuts down the script and resets state.
     */
    @Override
    public void shutdown() {
        if (mainScheduledFuture != null && !mainScheduledFuture.isCancelled()) {
            mainScheduledFuture.cancel(true);
        }
        lapCount = 0;
        dispenserLoots = 0;
        startTime = 0;
        currentState = ObstacleState.START;
        pitRecoveryTarget = null;
        isWaitingForPipe = false;
        isWaitingForRope = false;
        isWaitingForStones = false;
        isWaitingForLog = false;
        dispenserLootAttempts = 0;
        ropeRecoveryWalked = false;
        pipeJustCompleted = false;
        // Reset position tracking variables
        lastTrackedPosition = null;
        positionLastChangedTime = 0;
        lastPositionCheckTime = 0;
        currentStateRetryAttempts = 0;
        isInRetryMode = false;
        Microbot.log("[WildernessAgilityScript] shutdown called");
        super.shutdown();
    }

    private void info(String msg) {
        // Only log dispenser value
        Microbot.log(msg);
        System.out.println(msg);
    }

    public boolean runeliteClientPluginsMicrobotWildernessagilityWildernessAgilityScript_run(WildernessAgilityConfig config) {
        return run(config);
    }

    private void handlePlayerDeath() {
        if (!config.runBack()) {
            if (config.logoutAfterDeath()) {
                sleep(12000);
                attemptLogoutUntilLoggedOut();
            }
            Microbot.stopPlugin(plugin);
            return;
        }
        sleep(10000);
        Rs2Bank.walkToBank();
        currentState = ObstacleState.BANKING;
    }

    private boolean waitForInventoryChanges(int timeoutMs) {
        List<Rs2ItemModel> before = Rs2Inventory.items().collect(Collectors.toList());
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs && isRunning()) {
            List<Rs2ItemModel> after = Rs2Inventory.items().collect(Collectors.toList());
            if (after.size() != before.size()) return true;
            sleep(50);
        }
        return false;
    }

    public int getInventoryValue() {
        return Rs2Inventory.items().filter(Objects::nonNull).mapToInt(Rs2ItemModel::getPrice).sum();
    }

    public String getRunningTime() {
        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = (elapsed / 1000) % 60;
        long minutes = (elapsed / (1000 * 60)) % 60;
        long hours = (elapsed / (1000 * 60 * 60));
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public void setPlugin(WildernessAgilityPlugin plugin) {
        this.plugin = plugin;
    }

    private TileObject getDispenserObj() {
        return Rs2GameObject.getAll(o -> o.getId() == DISPENSER_ID, 104).stream().findFirst().orElse(null);
    }
    private TileObject getObstacleObj(int index) {
        return Rs2GameObject.getAll(o -> o.getId() == obstacles.get(index).getObjectId(), 104).stream().findFirst().orElse(null);
    }
    private boolean isUnderground(WorldPoint loc) {
        return loc != null && loc.getY() > 10000;
    }
    private void recoverFromPit() {
        // First check if we're still in the pit
        if (isUnderground(Rs2Player.getWorldLocation())) {
            // Immediately refresh ladder object before attempting to interact
            List<TileObject> ladders = Rs2GameObject.getAll(o -> o.getId() == 17385, 104);
            TileObject ladderObj = ladders.isEmpty() ? null : ladders.get(0);
            long now = System.currentTimeMillis();
            if (ladderObj != null && Rs2Player.getWorldLocation().distanceTo(ladderObj.getWorldLocation()) <= 50) {
                // Only attempt to interact with the ladder every 2 seconds
                if (now - lastLadderInteractTime > 2000) {
                    // Refresh ladder object again just before interaction
                    List<TileObject> laddersNow = Rs2GameObject.getAll(o -> o.getId() == 17385, 104);
                    ladderObj = laddersNow.isEmpty() ? null : laddersNow.get(0);
                    Rs2GameObject.interact(ladderObj, "Climb-up");
                    lastLadderInteractTime = now;
                }
            }
            return; // Return here to let the next tick handle the state transition
        }

        // If we're here, we've successfully climbed out of the pit
        if (pitRecoveryTarget != null) {
            switch (pitRecoveryTarget) {
                case ROPE:
                    // Fast walk back to rope, but only once
                    WorldPoint ropePoint = new WorldPoint(3005, 3953, 0);
                    if (!ropeRecoveryWalked) {
                        Rs2Walker.walkFastCanvas(ropePoint);
                        ropeRecoveryWalked = true;
                    }
                    if (Rs2Player.getWorldLocation().distanceTo(ropePoint) > 1) {
                        // Not close enough yet? wait for main loop to walk
                        return;
                    }
                    sleep(300, 600);

                    // Now interact with rope
                    TileObject rope = getObstacleObj(1);
                    if (rope != null && !Rs2Player.isMoving()) {
                        isWaitingForRope = false;
                        ropeStartXp = Microbot.getClient().getSkillExperience(AGILITY);
                        boolean interacted = Rs2GameObject.interact(rope);
                        if (interacted) {
                            isWaitingForRope = true;
                        }
                    }
                    break;

                case LOG:
                    // Wide range detection for log, just like ladder detection
                    List<TileObject> logs = Rs2GameObject.getAll(o -> o.getId() == obstacles.get(3).getObjectId(), 104);
                    TileObject log = logs.isEmpty() ? null : logs.get(0);
                    if (log != null) {
                        isWaitingForLog = false;
                        boolean interacted = Rs2GameObject.interact(log);
                        if (interacted) {
                            isWaitingForLog = true;
                            sleep(300, 600);
                        }
                    }
                    break;

                default:
                    break;
            }

            currentState = pitRecoveryTarget;
            pitRecoveryTarget = null;
            ropeRecoveryWalked = false; // Reset after recovery
        } else {
            // This should never happen now that we properly set pitRecoveryTarget
            WorldPoint ropeStart = new WorldPoint(3005, 3953, 0);
            Rs2Walker.walkFastCanvas(ropeStart);
            sleepUntil(() -> !Rs2Player.isMoving(), 5000);
            currentState = ObstacleState.ROPE;
            isWaitingForRope = false;
            ropeRecoveryWalked = false; // Reset if no recovery target
        }
    }
    private void handlePipe() {
        if (isWaitingForPipe) {
            // Use XP drop to confirm pipe completion
            if (waitForXpChange(pipeStartXp, getXpTimeout())) {
                isWaitingForPipe = false;
                pipeJustCompleted = false; // Clear after XP drop
                currentState = ObstacleState.ROPE;
                return;
            }
            // Fail fast: if no animation/movement after failTimeoutMs, abort and retry
            if (hasTimedOutSince(pipeInteractionStartTime, config.failTimeoutMs()) && !Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
                isWaitingForPipe = false;
                pipeJustCompleted = false;
                return;
            }
            return;
        }
        WorldPoint loc = Rs2Player.getWorldLocation();
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            // Player must be within 4 tiles of (3004, 3937, 0) to interact with the pipe at (3004, 3938, 0)
            WorldPoint pipeTile = new WorldPoint(3004, 3938, 0);
            WorldPoint pipeFrontTile = new WorldPoint(3004, 3937, 0);
            int distanceToPipeFront = loc.distanceTo(pipeFrontTile);
            if (distanceToPipeFront > 4) {
                if (!isAt(pipeFrontTile, 4)) {
                    Rs2Walker.walkTo(pipeFrontTile, 2);
                }
                return;
            }
            // Find the pipe object at the exact tile (3004, 3938, 0)
            TileObject pipe = Rs2GameObject.getAll(o -> o.getId() == obstacles.get(0).getObjectId() &&
                                                    o.getWorldLocation().equals(pipeTile), 10)
                                        .stream().findFirst().orElse(null);
            if (pipe == null) {
                return;
            }
            boolean interacted = Rs2GameObject.interact(pipe);
            if (interacted) {
                isWaitingForPipe = true;
                pipeJustCompleted = true; // Set immediately after interaction
                pipeStartXp = Microbot.getClient().getSkillExperience(AGILITY);
                pipeInteractionStartTime = System.currentTimeMillis();
            }
        }
    }
    private void handleRope() {
        pipeJustCompleted = false;
        WorldPoint loc = Rs2Player.getWorldLocation();
        // Always check for pitfall first, before any other logic
        if (isUnderground(loc)) {
            if (pitRecoveryTarget != ObstacleState.ROPE) {
                pitRecoveryTarget = ObstacleState.ROPE;
                currentState = ObstacleState.PIT_RECOVERY;
            }
            isWaitingForRope = false;
            return;
        }
        if (isWaitingForRope) {
            // Immediately check for XP gain or Y >= 3958
            if (waitForXpChange(ropeStartXp, getXpTimeout()) || (loc != null && loc.getY() >= 3958)) {
                isWaitingForRope = false;
                currentState = ObstacleState.STONES;
                return;
            }
            // Fail fast: if no animation/movement after failTimeoutMs, abort and retry
            if (hasTimedOutSince(lastObstacleInteractTime, config.failTimeoutMs()) && !Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
                isWaitingForRope = false;
                return;
            }
            return;
        }
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            TileObject rope = getObstacleObj(1);
            if (rope != null) {
                boolean interacted = Rs2GameObject.interact(rope);
                if (interacted) {
                    isWaitingForRope = true;
                    ropeStartXp = Microbot.getClient().getSkillExperience(AGILITY);
                    lastObstacleInteractTime = System.currentTimeMillis();
                    lastObstaclePosition = Rs2Player.getWorldLocation();
                }
            }
        }
    }
    private void handleStones() {
        if (isWaitingForStones) {
            WorldPoint loc = Rs2Player.getWorldLocation();
            int currentXp = Microbot.getClient().getSkillExperience(AGILITY);
            boolean yPassed = loc != null && loc.getY() > 3961;
            boolean xPassed = loc != null && loc.getX() == 2996;
            boolean xpPassed = currentXp > stonesStartXp;
            if (yPassed) {
                isWaitingForStones = false;
                return;
            }
            if (xPassed) {
                isWaitingForStones = false;
                currentState = ObstacleState.LOG;
                return;
            }
            if (xpPassed) {
                isWaitingForStones = false;
                currentState = ObstacleState.LOG;
                return;
            }
            // Fail fast: only if not animating, not moving, and not making progress
            if (hasTimedOutSince(lastObstacleInteractTime, config.failTimeoutMs())
                && !Rs2Player.isAnimating()
                && !Rs2Player.isMoving()
                && !yPassed && !xPassed && !xpPassed) {
                isWaitingForStones = false;
                return;
            }
            return;
        }
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            WorldPoint loc = Rs2Player.getWorldLocation();
            TileObject stones = getObstacleObj(2);
            if (stones != null) {
                boolean interacted = Rs2GameObject.interact(stones);
                if (interacted) {
                    isWaitingForStones = true;
                    stonesStartXp = Microbot.getClient().getSkillExperience(AGILITY);
                    lastObstacleInteractTime = System.currentTimeMillis();
                    lastObstaclePosition = Rs2Player.getWorldLocation();
                }
            }
        }
    }
    private void handleLog() {
        if (isWaitingForLog) {
            WorldPoint loc = Rs2Player.getWorldLocation();
            boolean xCoordPassed = loc != null && loc.getX() == 2994;
            boolean xpPassed = Microbot.getClient().getSkillExperience(AGILITY) > logStartXp;
            if (xCoordPassed || xpPassed) {
                isWaitingForLog = false;
                currentState = ObstacleState.ROCKS;
                return;
            }
            if (isUnderground(loc)) {
                if (pitRecoveryTarget != ObstacleState.LOG) {
                    pitRecoveryTarget = ObstacleState.LOG;
                    currentState = ObstacleState.PIT_RECOVERY;
                }
                return;
            }
            // Fail fast: if no animation/movement after failTimeoutMs, abort and retry
            if (hasTimedOutSince(lastObstacleInteractTime, config.failTimeoutMs()) && !Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
                isWaitingForLog = false;
                return;
            }
        }
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            // Clear inventory before attempting obstacle if needed
            clearInventoryIfNeeded();
            
            TileObject log = getObstacleObj(3);
            if (log == null) {
                List<TileObject> logs = Rs2GameObject.getAll(o -> o.getId() == obstacles.get(3).getObjectId(), 104);
                log = logs.isEmpty() ? null : logs.get(0);
            }
            if (log != null) {
                boolean interacted = Rs2GameObject.interact(log);
                if (interacted) {
                    isWaitingForLog = true;
                    logStartXp = Microbot.getClient().getSkillExperience(AGILITY);
                    lastObstacleInteractTime = System.currentTimeMillis();
                    lastObstaclePosition = Rs2Player.getWorldLocation();
                }
            }
        } else {
            // Check for pit fall while moving/animating
            WorldPoint loc = Rs2Player.getWorldLocation();
            if (isUnderground(loc)) {
                isWaitingForLog = false;
                pitRecoveryTarget = ObstacleState.LOG;  // Set recovery target for wide detection
                currentState = ObstacleState.PIT_RECOVERY;
            }
        }
    }
    private void handleRocks() {
        WorldPoint loc = Rs2Player.getWorldLocation();
        if (loc != null && loc.getY() <= 3933) {
            // Get fresh dispenser object for immediate use
            TileObject freshDispenser = getDispenserObj();
            cachedDispenserObj = freshDispenser;
            lastObjectCheck = System.currentTimeMillis();

            currentState = ObstacleState.DISPENSER;

            // Immediate interaction without waiting for next tick
            if (!Rs2Player.isAnimating() && freshDispenser != null) {
                dispenserTicketsBefore = Rs2Inventory.itemQuantity(TICKET_ITEM_ID);
                dispenserPreValue = getInventoryValue();
                dispenserLootAttempts = 1;
                waitingForDispenserLoot = true;
                Rs2GameObject.interact(freshDispenser, "Search");
            }
            return;
        }

        clearInventoryIfNeeded();
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            TileObject rocks = getObstacleObj(4);
            if (rocks == null) {
                List<TileObject> rocksList = Rs2GameObject.getAll(o -> o.getId() == obstacles.get(4).getObjectId(), 104);
                rocks = rocksList.isEmpty() ? null : rocksList.get(0);
            }
            if (rocks != null) {
                int startExp = Microbot.getClient().getSkillExperience(AGILITY);
                if (Rs2GameObject.interact(rocks)) {
                    // Check immediately after interaction
                    WorldPoint newLoc = Rs2Player.getWorldLocation();
                    if (newLoc != null && newLoc.getY() <= 3934) {
                        // Transition immediately
                        TileObject freshDispenser = getDispenserObj();
                        cachedDispenserObj = freshDispenser;
                        lastObjectCheck = System.currentTimeMillis();
                        currentState = ObstacleState.DISPENSER;
                        if (!Rs2Player.isAnimating() && freshDispenser != null) {
                            dispenserTicketsBefore = Rs2Inventory.itemQuantity(TICKET_ITEM_ID);
                            dispenserPreValue = getInventoryValue();
                            dispenserLootAttempts = 1;
                            waitingForDispenserLoot = true;
                            Rs2GameObject.interact(freshDispenser, "Search");
                        }
                        return;
                    }
                    if (waitForXpChange(startExp, getXpTimeout())) {
                        currentState = ObstacleState.DISPENSER;
                        return;
                    }
                }
            }
            // Fallback: if player Y < 3934, consider rocks completed
            loc = Rs2Player.getWorldLocation();
            if (loc != null && loc.getY() < 3934) {
                currentState = ObstacleState.DISPENSER;
            }
        }
    }
    private void handleDispenser() {
        TileObject dispenser = cachedDispenserObj;
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (dispenser == null || playerLoc == null) return;
        if (playerLoc.distanceTo(dispenser.getWorldLocation()) > 20) return;

        // Update inventory value only here
        cachedInventoryValue = getInventoryValue();

        int currentTickets = Rs2Inventory.itemQuantity(TICKET_ITEM_ID);

        // If we're waiting for loot, check for ticket gain regardless of animation state
        if (waitingForDispenserLoot) {
            if (Rs2Inventory.itemQuantity(TICKET_ITEM_ID) > dispenserTicketsBefore) {
                long now = System.currentTimeMillis();
                if (lastLapTimestamp > 0) {
                    previousLapTime = now - lastLapTimestamp;
                    if (previousLapTime < fastestLapTime) {
                        fastestLapTime = previousLapTime;
                    }
                }
                lastLapTimestamp = now;
                dispenserLoots++;
                lapCount++;
                int dispenserValue = getInventoryValue() - dispenserPreValue;
                String formattedValue = NumberFormat.getIntegerInstance().format(dispenserValue);
                info("Dispenser Value: " + formattedValue);
                currentState = ObstacleState.CONFIG_CHECKS;
                dispenserLootAttempts = 0;
                waitingForDispenserLoot = false;
            }
            return;
        }

        // If the player is already animating (interacting with the dispenser), do not interact again
        if (Rs2Player.isAnimating()) return;

        // Try to interact with the dispenser every tick until animation starts
        if (dispenserLootAttempts == 0) {
            dispenserPreValue = getInventoryValue();
            dispenserTicketsBefore = currentTickets;
            Rs2GameObject.interact(dispenser, "Search");
            waitingForDispenserLoot = true;
            dispenserLootAttempts = 1; // Only try once, now wait for loot
        } else if (dispenserLootAttempts == 1) {
            // If for some reason we didn't get loot after a while, allow retry or fallback
            // (Optional: add a timeout here if needed)
        }
    }
    private void handleConfigChecks() {
        TileObject dispenser = cachedDispenserObj;
        if (dispenser == null) return;
        int ticketCount = Rs2Inventory.itemQuantity(TICKET_ITEM_ID);
        if (ticketCount >= config.useTicketsWhen()) {
            boolean didInteract = Rs2Inventory.interact(TICKET_ITEM_ID, "Use");
            if (didInteract) {
                didInteract = Rs2GameObject.interact(dispenser, "Use");
                if (didInteract) {
                    sleepUntil(() -> Rs2Inventory.itemQuantity(TICKET_ITEM_ID) < ticketCount, 2000);
                }
            }
        }
        // Force banking if config.bankAfterDispensers() > 0 and dispenserLoots >= threshold
        if (config.bankAfterDispensers() > 0 && dispenserLoots >= config.bankAfterDispensers()) {
            if (config.enableWorldHop()) {
                setupWorldHop();
                currentState = ObstacleState.WORLD_HOP_1;
            } else {
                currentState = ObstacleState.WALK_TO_LEVER;
            }
            return;
        }
        // Force banking if config.bankNow() is enabled
        if (config.bankNow() || forceBankNextLoot) {
            forceBankNextLoot = false;
            if (config.enableWorldHop()) {
                setupWorldHop();
                currentState = ObstacleState.WORLD_HOP_1;
            } else {
                currentState = ObstacleState.WALK_TO_LEVER;
            }
            return;
        }
        // Only check banking threshold here
        if (cachedInventoryValue >= config.leaveAtValue()) {
            if (config.enableWorldHop()) {
                setupWorldHop();
                currentState = ObstacleState.WORLD_HOP_1;
            } else {
                currentState = ObstacleState.WALK_TO_LEVER;
            }
            return;
        }
        currentState = ObstacleState.PIPE;
        dispenserLootAttempts = 0;
        // Immediately call handlePipe() if player is ready
        if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            WorldPoint pipeFrontTile = new WorldPoint(3004, 3937, 0);
            WorldPoint loc = Rs2Player.getWorldLocation();
            if (loc != null && loc.distanceTo(pipeFrontTile) <= 4) {
                handlePipe();
            }
        }
    }
    private void handleStart() {
        TileObject dispenserObj = getDispenserObj();
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        boolean nearDispenser = dispenserObj != null && playerLoc != null && playerLoc.distanceTo(dispenserObj.getWorldLocation()) <= 4;

        if (!(forceStartAtCourse || config.startAtCourse())) {
            if (!nearDispenser) {
                WorldPoint walkTarget = dispenserObj != null ? dispenserObj.getWorldLocation() : DISPENSER_POINT;
                if (!isAt(walkTarget, 4)) {
                    Rs2Walker.walkTo(walkTarget, 2);
                    return;
                }
            }
            int coinCount = Rs2Inventory.itemQuantity("Coins");
            if (coinCount < 150000) {
                currentState = ObstacleState.BANKING;
                return;
            }
            if (dispenserObj != null) {
                Rs2Inventory.use("Coins");
                sleep(400);
                Rs2GameObject.interact(dispenserObj, "Use");
                sleep(getActionDelay());
                sleepUntil(() -> Rs2Inventory.itemQuantity("Coins") < coinCount, getXpTimeout());
            }
            currentState = ObstacleState.PIPE;
            return;
        } else {
            if (!nearDispenser) {
                WorldPoint walkTarget = dispenserObj != null ? dispenserObj.getWorldLocation() : DISPENSER_POINT;
                if (!isAt(walkTarget, 4)) {
                    Rs2Walker.walkTo(walkTarget, 2);
                    return;
                }
            }
            sleep(300, 600);
            currentState = ObstacleState.PIPE;
        }
    }

    /**
     * Handles position-based timeout and retry logic
     */
    private void handlePositionTimeoutLogic() {
        WorldPoint currentPosition = Rs2Player.getWorldLocation();
        long currentTime = System.currentTimeMillis();
        
        // Only check position every POSITION_CHECK_INTERVAL to avoid spam
        if (currentTime - lastPositionCheckTime < POSITION_CHECK_INTERVAL) {
            return;
        }
        lastPositionCheckTime = currentTime;
        
        // Skip position tracking for certain states that don't need it
        if (shouldSkipPositionTracking()) {
            resetPositionTracking();
            return;
        }
        
        // Initialize position tracking if needed
        if (lastTrackedPosition == null) {
            resetPositionTracking();
            lastTrackedPosition = currentPosition;
            positionLastChangedTime = currentTime;
            return;
        }
        
        // Check if position has changed
        if (currentPosition != null && !currentPosition.equals(lastTrackedPosition)) {
            lastTrackedPosition = currentPosition;
            positionLastChangedTime = currentTime;
            currentStateRetryAttempts = 0;
            isInRetryMode = false;
            return;
        }
        
        // Position hasn't changed - check for timeout
        long timeSinceLastMove = currentTime - positionLastChangedTime;
        if (timeSinceLastMove >= getPositionTimeout()) {
            handlePositionTimeout();
        }
    }
    
    /**
     * Handles what happens when position timeout is reached
     */
    private void handlePositionTimeout() {
        if (!isInRetryMode) {
            // First timeout - retry current state
            if (currentState == ObstacleState.PIT_RECOVERY) {
                Microbot.log("[Position Timeout] Player stuck in pit for " + getPositionTimeout() + "ms. Retrying ladder interaction...");
            } else {
                Microbot.log("[Position Timeout] Player stuck in " + currentState + " for " + getPositionTimeout() + "ms. Retrying...");
            }
            isInRetryMode = true;
            currentStateRetryAttempts++;
            retryCurrentState();
            resetPositionTracking();
        } else if (currentStateRetryAttempts >= MAX_RETRY_ATTEMPTS) {
            // Second timeout after retry - move to next state
            if (currentState == ObstacleState.PIT_RECOVERY) {
                Microbot.log("[Position Timeout] Still stuck in pit after retry. Forcing exit from pit recovery...");
            } else {
                Microbot.log("[Position Timeout] Retry failed for " + currentState + ". Moving to next state...");
            }
            forceProgressToNextState();
            resetPositionTracking();
        }
    }
    
    /**
     * Determines if position tracking should be skipped for the current state
     */
    private boolean shouldSkipPositionTracking() {
        return currentState == ObstacleState.BANKING || 
               currentState == ObstacleState.WORLD_HOP_1 || 
               currentState == ObstacleState.WORLD_HOP_2 ||
               currentState == ObstacleState.WALK_TO_LEVER ||
               currentState == ObstacleState.WALK_TO_COURSE ||
               Rs2Player.isMoving() ||
               Rs2Player.isAnimating();
    }
    
    /**
     * Resets position tracking variables
     */
    private void resetPositionTracking() {
        lastTrackedPosition = Rs2Player.getWorldLocation();
        positionLastChangedTime = System.currentTimeMillis();
        currentStateRetryAttempts = 0;
        isInRetryMode = false;
    }
    
    /**
     * Retries the current obstacle state
     */
    private void retryCurrentState() {
        switch (currentState) {
            case PIPE:
                isWaitingForPipe = false;
                break;
            case ROPE:
                isWaitingForRope = false;
                break;
            case STONES:
                isWaitingForStones = false;
                break;
            case LOG:
                isWaitingForLog = false;
                break;
            case ROCKS:
                // No waiting state for rocks
                break;
            case DISPENSER:
                waitingForDispenserLoot = false;
                dispenserLootAttempts = 0;
                break;
            case PIT_RECOVERY:
                // Reset ladder interaction time to allow immediate retry
                lastLadderInteractTime = 0;
                ropeRecoveryWalked = false;
                break;
            default:
                break;
        }
    }
    
    /**
     * Forces progression to the next state in the obstacle sequence
     */
    private void forceProgressToNextState() {
        switch (currentState) {
            case PIPE:
                Microbot.log("[Force Progress] Moving from PIPE to ROPE");
                currentState = ObstacleState.ROPE;
                isWaitingForPipe = false;
                break;
            case ROPE:
                Microbot.log("[Force Progress] Moving from ROPE to STONES");
                currentState = ObstacleState.STONES;
                isWaitingForRope = false;
                break;
            case STONES:
                Microbot.log("[Force Progress] Moving from STONES to LOG");
                currentState = ObstacleState.LOG;
                isWaitingForStones = false;
                break;
            case LOG:
                Microbot.log("[Force Progress] Moving from LOG to ROCKS");
                currentState = ObstacleState.ROCKS;
                isWaitingForLog = false;
                break;
            case ROCKS:
                Microbot.log("[Force Progress] Moving from ROCKS to DISPENSER");
                currentState = ObstacleState.DISPENSER;
                break;
            case DISPENSER:
                Microbot.log("[Force Progress] Moving from DISPENSER to CONFIG_CHECKS");
                currentState = ObstacleState.CONFIG_CHECKS;
                waitingForDispenserLoot = false;
                dispenserLootAttempts = 0;
                break;
            case PIT_RECOVERY:
                Microbot.log("[Force Progress] Stuck in pit recovery, forcing exit and continuing...");
                // Force the player to move to the recovery target or default to ROPE
                if (pitRecoveryTarget != null) {
                    currentState = pitRecoveryTarget;
                    Microbot.log("[Force Progress] Returning to recovery target: " + pitRecoveryTarget);
                } else {
                    currentState = ObstacleState.ROPE;
                    Microbot.log("[Force Progress] No recovery target set, defaulting to ROPE");
                }
                // Reset pit recovery variables
                pitRecoveryTarget = null;
                ropeRecoveryWalked = false;
                lastLadderInteractTime = 0;
                break;
            default:
                Microbot.log("[Force Progress] No progression defined for state: " + currentState);
                break;
        }
    }

    private boolean waitForXpChange(int startXp, int timeoutMs) {
        return sleepUntil(() -> Microbot.getClient().getSkillExperience(AGILITY) > startXp, timeoutMs);
    }

    private boolean isAt(WorldPoint target, int dist) {
        WorldPoint loc = Rs2Player.getWorldLocation();
        return loc != null && target != null && loc.distanceTo(target) <= dist;
    }

    private int getActionDelay() { return ACTION_DELAY; }
    private int getXpTimeout() { return XP_TIMEOUT; }

    /**
     * Gets the position timeout value from config (same as animation fail timeout)
     * @return timeout in milliseconds from config
     */
    private int getPositionTimeout() {
        return config.failTimeoutMs();
    }

    public String getPreviousLapTime() {
        if (previousLapTime == 0) return "-";
        return String.format("%.2f s", previousLapTime / 1000.0);
    }

    public String getFastestLapTime() {
        if (fastestLapTime == Long.MAX_VALUE) return "-";
        return String.format("%.2f s", fastestLapTime / 1000.0);
    }

    private void setupWorldHop() {
        originalWorld = Rs2Player.getWorld();
        bankWorld1 = getConfigWorld(config.bankWorld1());
        bankWorld2 = getConfigWorld(config.bankWorld2());
    }

    private int getConfigWorld(WildernessAgilityConfig.BankWorldOption option) {
        if (option == WildernessAgilityConfig.BankWorldOption.Random) {
            // Pick a random world from the enum list, skipping the current world
            List<WildernessAgilityConfig.BankWorldOption> all = Arrays.asList(WildernessAgilityConfig.BankWorldOption.values());
            List<Integer> worldNums = new ArrayList<>();
            for (WildernessAgilityConfig.BankWorldOption o : all) {
                if (o != WildernessAgilityConfig.BankWorldOption.Random) {
                    int num = Integer.parseInt(o.name().substring(1));
                    if (num != Rs2Player.getWorld()) worldNums.add(num);
                }
            }
            if (worldNums.isEmpty()) return Rs2Player.getWorld();
            return worldNums.get(new Random().nextInt(worldNums.size()));
        } else {
            return Integer.parseInt(option.name().substring(1));
        }
    }

    private void handleWorldHop1() {
        if (Rs2Player.getWorld() != bankWorld1) {
            Microbot.hopToWorld(bankWorld1);
            boolean hopConfirmed = sleepUntil(() -> Rs2Player.getWorld() == bankWorld1, 8000);
            if (!hopConfirmed) {
                return; // Stay in this state to retry
            }
        }
        sleep(4000); // Wait 4 seconds after hop
        leaveFriendChat();
        currentState = ObstacleState.WORLD_HOP_2;
    }

    private void handleWorldHop2() {
        if (Rs2Player.getWorld() != bankWorld2) {
            Microbot.hopToWorld(bankWorld2);
            boolean hopConfirmed = sleepUntil(() -> Rs2Player.getWorld() == bankWorld2, 8000);
            if (!hopConfirmed) {
                return; // Stay in this state to retry
            }
        }
        currentState = ObstacleState.WALK_TO_LEVER;
    }

    private void handleSwapBack() {
        if (Rs2Player.getWorld() == originalWorld) {
            if (config.joinFc()) {
                joinFriendChat();
            }
            currentState = ObstacleState.PIPE;
            return;
        }
        Microbot.hopToWorld(originalWorld);
        sleepUntil(() -> Rs2Player.getWorld() == originalWorld, 8000);
        if (config.joinFc()) {
            joinFriendChat();
        }
        currentState = ObstacleState.PIPE;
    }

    private void leaveFriendChat() {
        // Click the Leave button in the chat-channel tab if visible
        Widget joinLeaveButton = Rs2Widget.getWidget(458, 770);
        if (joinLeaveButton != null && !joinLeaveButton.isHidden()) {
            if (joinLeaveButton.getText().equalsIgnoreCase("Leave")) {
                Microbot.getMouse().click(joinLeaveButton.getCanvasLocation());
                sleep(400, 600);
                // Wait until the Join button is visible and says 'Join'
                sleepUntil(() -> {
                    Widget joinBtn = Rs2Widget.getWidget(458, 770);
                    return joinBtn != null && !joinBtn.isHidden() && joinBtn.getText().equalsIgnoreCase("Join");
                }, 5000);
            }
        }
    }

    private void joinFriendChat() {
        joinChatChannel(config.fcChannel());
    }

    private void sendChatChannelTabMenuAction() {
        try {
            Widget chatTab = Rs2Widget.getWidget(10551339);
            java.awt.Rectangle bounds = chatTab != null ? chatTab.getBounds() : new java.awt.Rectangle(1277, 807, 38, 36); // fallback to known values
            Microbot.doInvoke(new net.runelite.client.plugins.microbot.util.menu.NewMenuEntry(
                "Chat-channel", // option
                -1,             // param0
                10551339,       // param1
                net.runelite.api.MenuAction.CC_OP.getId(), // opcode
                1,              // identifier
                -1,             // itemId
                ""              // target
            ), bounds);
        } catch (Exception ex) {
            Microbot.log("[FC JOIN] Exception in sendChatChannelTabMenuAction: " + ex.getMessage());
        }
    }

    private void sendJoinButtonMenuAction() {
        try {
            Widget joinButton = Rs2Widget.getWidget(458, 770);
            java.awt.Rectangle bounds = joinButton != null ? joinButton.getBounds() : new java.awt.Rectangle(0, 0, 30, 20); // fallback
            Microbot.doInvoke(new net.runelite.client.plugins.microbot.util.menu.NewMenuEntry(
                "Join", // option
                -1,      // param0
                458770,  // param1
                net.runelite.api.MenuAction.CC_OP.getId(), // opcode
                1,       // identifier
                -1,      // itemId
                ""      // target
            ), bounds);
        } catch (Exception ex) {
            Microbot.log("[FC JOIN] Exception in sendJoinButtonMenuAction: " + ex.getMessage());
        }
    }

    private void joinChatChannel(String channelName) {
        sleep(3000);
        sendChatChannelTabMenuAction();
        sleep(3000);
        sleep(1000);
        sendJoinButtonMenuAction();
        sleep(2000);
        sleep(1000);
        final long firstJoinAttemptTime = System.currentTimeMillis();
        Rs2Keyboard.typeString(channelName);
        Rs2Keyboard.enter();
        sleep(600, 900);
        // Wait for FC join confirmation message
        boolean joined = sleepUntil(() -> lastFcJoinMessageTime > firstJoinAttemptTime, 5000);
        if (!joined) {
            // Retry once more
            final long secondJoinAttemptTime = System.currentTimeMillis();
            Rs2Keyboard.typeString(channelName);
            Rs2Keyboard.enter();
            sleep(600, 900);
            joined = sleepUntil(() -> lastFcJoinMessageTime > secondJoinAttemptTime, 5000);
            if (!joined) { //need to fix this - function isnt working well rn.
            }
        }
    }

    private void handleWalkToLever() {
        WorldPoint leverTile = new WorldPoint(3091, 3956, 0);
        if (!isAt(leverTile, 2)) {
            Rs2Walker.walkTo(leverTile);
            sleepUntil(() -> isAt(leverTile, 2), 20000);
            return;
        }
        currentState = ObstacleState.INTERACT_LEVER;
    }

    private void handleInteractLever() {
        WorldPoint leverTile = new WorldPoint(3091, 3956, 0);
        if (!isAt(leverTile, 2)) {
            // If not at lever, go back to walk to lever state
            currentState = ObstacleState.WALK_TO_LEVER;
            return;
        }
        TileObject lever = Rs2GameObject.findObjectById(5959);
        if (lever != null) {
            Rs2GameObject.interact(lever, "Pull");
            boolean animDetected = sleepUntil(() -> Rs2Player.getAnimation() == 2140, 8000);
            if (animDetected) {
                sleep(5000); // Wait for teleport
                currentState = ObstacleState.BANKING;
            } else {
            }
        }
    }

    private boolean isInFriendChat() {
        Widget fcWidget = Rs2Widget.getWidget(162, 72);
        return fcWidget != null && !fcWidget.isHidden() && fcWidget.getText() != null && !fcWidget.getText().toLowerCase().contains("join");
    }

    private void handlePostBankConfig() {
        forceBankNextLoot = false;
        if (config.swapBack() && Rs2Player.getWorld() != originalWorld) {
            Microbot.hopToWorld(originalWorld);
            sleepUntil(() -> Rs2Player.getWorld() == originalWorld, 8000);
        }
        if (config.joinFc()) {
            joinFriendChat();
        }
        // Force disable startAtCourse after banking
        forceStartAtCourse = false;
        currentState = ObstacleState.WALK_TO_COURSE;
    }

    private void handleWalkToCourse() {
        if (!isAt(START_POINT, 2)) {
            Rs2Walker.walkTo(START_POINT, 2);
            sleepUntil(() -> isAt(START_POINT, 2), 20000);
            return;
        }
        TileObject dispenserObj = getDispenserObj();
        if (dispenserObj != null) {
            int coinCount = Rs2Inventory.itemQuantity("Coins");
            if (coinCount >= 150000) {
                Rs2Inventory.use("Coins");
                sleep(400);
                Rs2GameObject.interact(dispenserObj, "Use");
                sleep(getActionDelay());
                sleepUntil(() -> Rs2Inventory.itemQuantity("Coins") < coinCount, getXpTimeout());
            }
        }
        currentState = ObstacleState.PIPE;
    }

    private void handleBanking() {
        // Single-step banking logic
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 20000);
            if (!Rs2Bank.isOpen()) return;
        }
        // Deposit all
        Rs2Bank.depositAll();
        sleep(getActionDelay());

        // Withdraw Knife (if enabled)
        if (config.withdrawKnife() && !Rs2Inventory.hasItem(KNIFE_ID)) {
            Rs2Bank.withdrawOne(KNIFE_ID);
            sleep(getActionDelay());
        }
        // Withdraw Teleport (if enabled)
        if (config.useIcePlateauTp() && !Rs2Inventory.hasItem(TELEPORT_ID)) {
            Rs2Bank.withdrawOne(TELEPORT_ID);
            sleep(getActionDelay());
        }
        // Withdraw Coins (if enabled)
        if (config.withdrawCoins() && (!Rs2Inventory.hasItem(COINS_ID) || Rs2Inventory.itemQuantity(COINS_ID) < 150000)) {
            Rs2Bank.withdrawX(COINS_ID, 150000);
            sleep(getActionDelay());
        }
        // Confirm all items are present
        boolean allPresent = (!config.withdrawKnife() || Rs2Inventory.hasItem(KNIFE_ID))
            && (!config.withdrawCoins() || Rs2Inventory.hasItem(COINS_ID))
            && (!config.useIcePlateauTp() || Rs2Inventory.hasItem(TELEPORT_ID));
        if (!allPresent) return;

        Rs2Bank.closeBank();
        sleep(getActionDelay());

        // Continue to next state
        currentState = ObstacleState.POST_BANK_CONFIG;
    }

    private void attemptLogoutUntilLoggedOut() {
        int maxAttempts = 30; // Try for up to 30 seconds
        int attempts = 0;
        while (!"LOGIN_SCREEN".equals(Microbot.getClient().getGameState().toString()) && attempts < maxAttempts) {
            Rs2Player.logout();
            sleep(1000); // Wait 1 second before trying again
            attempts++;
        }
    }

    private boolean hasTimedOutSince(long startTime, int threshold) {
        return System.currentTimeMillis() - startTime > threshold;
    }

    private void clearInventoryIfNeeded() {
        int attempts = 0;
        int maxAttempts = 10; // Prevent infinite loops
        
        while (Rs2Inventory.items().count() >= 26 && isRunning() && attempts < maxAttempts) {
            attempts++;
            boolean itemHandled = false;
            
            if (Rs2Inventory.contains(FOOD_PRIMARY)) {
                Rs2Inventory.interact(FOOD_PRIMARY, "Eat");
                waitForInventoryChanges(getActionDelay());
                itemHandled = true;
            } else if (Rs2Inventory.contains(FOOD_DROP)) {
                Rs2Inventory.interact(FOOD_DROP, "Drop");
                waitForInventoryChanges(800);
                itemHandled = true;
            } else if (Rs2Inventory.contains(FOOD_SECONDARY)) {
                Rs2Inventory.interact(FOOD_SECONDARY, "Eat");
                waitForInventoryChanges(getActionDelay());
                itemHandled = true;
            } else if (Rs2Inventory.contains(FOOD_TERTIARY)) {
                Rs2Inventory.interact(FOOD_TERTIARY, "Eat");
                waitForInventoryChanges(getActionDelay());
                itemHandled = true;
            }
            
            if (!itemHandled) {
                // If no known food items are found, try to drop any food items
                if (Rs2Inventory.count() >= 26) {
                    // Drop any item that's not essential (not knife, teleport, or coins)
                    Rs2Inventory.items().filter(item -> 
                        item.getId() != KNIFE_ID && 
                        item.getId() != TELEPORT_ID && 
                        item.getId() != COINS_ID &&
                        item.getId() != TICKET_ITEM_ID
                    ).findFirst().ifPresent(item -> {
                        Rs2Inventory.interact(item, "Drop");
                        waitForInventoryChanges(800);
                    });
                }
                break;
            }
        }
        
        if (attempts >= maxAttempts) {
            Microbot.log("clearInventoryIfNeeded() reached max attempts, breaking to prevent infinite loop");
        }
    }
    public int getDispenserLoots() {
        return dispenserLoots;
    }

    public void setLastFcJoinMessageTime(long time) {
        this.lastFcJoinMessageTime = time;
    }
}
