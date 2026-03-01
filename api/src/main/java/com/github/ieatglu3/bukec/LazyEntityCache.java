package com.github.ieatglu3.bukec;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Logger;

/**
 * Thread safe entity cache that lazily removes entities that are dead or in unloaded worlds using an internal GC
 */

// We are not using weak references because the cache may be heavily used, and we have more control this way
public final class LazyEntityCache
{

  public static boolean DEBUG = false;

  public static final Logger LOGGER = Logger.getLogger(LazyEntityCache.class.getName());

  private static ThreadFactory createGCExecutorThreadFactory()
  {
    return new ThreadFactory()
    {
      private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
      @Override
      public Thread newThread(Runnable r)
      {
        Thread thread = this.defaultFactory.newThread(r);
        thread.setName("LazyEntityCache-GC-Thread");
        return thread;
      }
    };
  }

  private final class EntityListener implements Listener
  {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
      final Entity player = event.getPlayer();
      final World world = player.getWorld();
      LazyEntityCache.this.cacheWorld(world);
      LazyEntityCache.this.cacheEntity(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
      LazyEntityCache.this.removeEntityNow(event.getPlayer().getEntityId());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
      final Entity player = event.getPlayer();
      final World newWorld = player.getWorld();
      LazyEntityCache.this.cacheWorld(newWorld);
      LazyEntityCache.this.cacheEntity(player);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event)
    {
      final Entity player = event.getPlayer();
      final World newWorld = player.getWorld();
      LazyEntityCache.this.cacheWorld(newWorld);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event)
    {
      LazyEntityCache.this.cacheWorld(event.getWorld());
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event)
    {
      final World world = event.getWorld();
      final UUID worldId = world.getUID();
      LazyEntityCache.this.removeWorldNow(worldId);
      LazyEntityCache.this.submitGCCycle();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event)
    {
      LazyEntityCache.this.cacheChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event)
    {
      final Chunk chunk = event.getChunk();
      for (Entity entity : chunk.getEntities())
        if (entity.getType() != EntityType.PLAYER)
          LazyEntityCache.this.removeEntityNow(entity.getEntityId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityAdd(EntitySpawnEvent event)
    {
      LazyEntityCache.this.cacheEntity(event.getEntity());
    }
  }

  /**
   * Creates a new cache with empty entity and world caches with a default GC executor and a default GC interval of 1 minute
   * @return new cache
   */
  public static LazyEntityCache create()
  {
    return create(Executors.newSingleThreadScheduledExecutor(createGCExecutorThreadFactory()), Duration.ofMinutes(1));
  }

  /**
   * Creates a new cache with empty entity and world caches with a default GC executor
   * @param gcInterval interval between GC cycles
   * @return new cache
   */
  public static LazyEntityCache create(Duration gcInterval)
  {
    return create(Executors.newSingleThreadScheduledExecutor(createGCExecutorThreadFactory()), gcInterval);
  }

  /**
   * Creates a new cache with empty entity and world caches and a custom GC executor
   * @param gcExecutor GC executor to use for the internal GC thread
   * @return new cache
   */
  public static LazyEntityCache create(ScheduledExecutorService gcExecutor, Duration gcInterval)
  {
    return new LazyEntityCache(
      new ConcurrentHashMap<>(),
      ConcurrentHashMap.newKeySet(),
      gcExecutor,
      gcInterval
    );
  }

  /**
   * Creates a new cache with the given entity and world caches and a custom GC executor
   * @param entities entity cache to use
   * @param worldIds world ID cache to use
   * @param gcExecutor GC executor
   * @param gcInterval interval between GC cycles
   * @return new cache
   */
  public static LazyEntityCache create(
    Map<Integer, Entity> entities,
    Set<UUID> worldIds,
    ScheduledExecutorService gcExecutor,
    Duration gcInterval
  )
  {
    return new LazyEntityCache(entities, worldIds, gcExecutor, gcInterval);
  }

  private final Map<Integer, Entity> entities;
  private final Set<UUID> worldIds;
  private final ScheduledExecutorService gcExecutor;
  private final Duration gcInterval;

  private volatile EntityListener entityListener = null;
  private static final AtomicReferenceFieldUpdater<LazyEntityCache, EntityListener> LISTENER =
    AtomicReferenceFieldUpdater.newUpdater(LazyEntityCache.class, EntityListener.class, "entityListener");

  private volatile ScheduledFuture<?> gcTask = null;
  private static final AtomicReferenceFieldUpdater<LazyEntityCache, ScheduledFuture> GC_TASK =
    AtomicReferenceFieldUpdater.newUpdater(LazyEntityCache.class, ScheduledFuture.class, "gcTask");

  LazyEntityCache(Map<Integer, Entity> entities, Set<UUID> worldIds, ScheduledExecutorService gcExecutor, Duration gcInterval)
  {
    this.entities = entities;
    this.worldIds = worldIds;
    this.gcExecutor = gcExecutor;
    this.gcInterval = gcInterval;
  }

  /**
   * Links the cache to a plugin, registering the necessary listeners for caching entities and worlds
   * <br><br>
   * This must be called before the cache can be used, and should only be called once per cache instance
   * @param plugin plugin
   */
  public void link(Plugin plugin)
  {
    final EntityListener listener = LISTENER.updateAndGet(this, existing -> {
      if (existing != null)
        throw new IllegalStateException("Cache is already linked to a plugin");
      return new EntityListener();
    });

    Bukkit.getServer().getPluginManager().registerEvents(listener, plugin);
    LOGGER.info("Linked to plugin '" + plugin.getName() + "' with GC interval of " + this.gcInterval);
  }

  /**
   * Unlinks the cache from its plugin, unregistering its listeners and clearing the cache
   * <br><br>
   * This does not shut down the GC task, or executor, you should call {@link #stopGC} or {@link #shutdownGCExecutor} to do that
   * <br><br>
   * @throws IllegalStateException if the cache is not linked
   */
  public void unlink()
  {
    final EntityListener listener = LISTENER.getAndSet(this, null);
    if (listener == null)
      throw new IllegalStateException("Cache is not linked to a plugin");
    HandlerList.unregisterAll(listener);
    this.clear();
    LOGGER.info("Unlinked from plugin and cleared cache");
  }

  /**
   * Starts the GC task
   * <br><br>
   * This should be called after linking the cache to a plugin, and should only be called once per cache instance
   * @throws IllegalStateException if the GC task is already running
   */
  public void startGC()
  {
    final long gcIntervalMillis = this.gcInterval.toMillis();
    GC_TASK.getAndUpdate(this, existing -> {
      if (existing != null)
        throw new IllegalStateException("GC is already running");
      return this.gcExecutor.scheduleAtFixedRate(this::gcCycle, gcIntervalMillis, gcIntervalMillis, TimeUnit.MILLISECONDS);
    });
    LOGGER.info("Started GC with interval of " + this.gcInterval);
  }

  /**
   * Stops the GC task
   * <br><br>
   * This does not shut down the GC executor, you should call {@link #shutdownGCExecutor} to do that
   * @throws IllegalStateException if the GC task is not running
   */
  public void stopGC()
  {
    final ScheduledFuture<?> gcTask = GC_TASK.getAndSet(this, null);
    if (gcTask == null)
      throw new IllegalStateException("GC is not running");
    if (!gcTask.cancel(false))
      LOGGER.warning("Failed to cancel GC task");
    LOGGER.info("Stopped GC");
  }

  /**
   * Returns true if the GC task is currently running, false otherwise
   * @return running
   */
  public boolean isGCRunning()
  {
    return this.gcTask != null;
  }

  /**
   * Returns the number of entities currently in the cache
   * @return entity count
   */
  public int entityCount()
  {
    return this.entities.size();
  }

  /**
   * Scans all loaded worlds and caches them and their entities
   * <br><br>
   * This should only be used if you need to cache worlds that were loaded before the cache was linked
   */
  public void scan()
  {
    for (final World world : Bukkit.getWorlds())
      this.cacheWorld(world);
  }

  /**
   * Shuts down the provided GC executor
   * <br><br>
   * If the provided executor is shared, you should call {@link #stopGC} to stop the GC task instead
   * @param timeout how long to wait for the GC executor to shut down before giving up
   * @return true if the GC executor shut down successfully, false if it timed out
   * @throws InterruptedException if the current thread was interrupted while waiting
   */
  public boolean shutdownGCExecutor(Duration timeout) throws InterruptedException
  {
    this.gcExecutor.shutdownNow();
    return this.gcExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Caches a world and all of its entities
   * <br><br>
   * This does nothing if the world is already cached
   * @param world world
   */
  public void cacheWorld(World world)
  {
    if (this.worldIds.add(world.getUID()))
      // todo; if initial entity counts are massive ( > 5000 or some shit), you may want to fragment this
      for (Entity entity : world.getEntities())
        this.cacheEntity(entity);
  }

  /**
   * Caches a chunk and all of its entities
   * @param chunk chunk
   */
  public void cacheChunk(Chunk chunk)
  {
    for (Entity entity : chunk.getEntities())
      this.cacheEntity(entity);
  }

  /**
   * Caches an entity
   * @param entity entity
   */
  public void cacheEntity(Entity entity)
  {
    this.entities.put(entity.getEntityId(), entity);
  }

  /**
   * Removes an entity immediately from the cache
   * @param entityId id
   */
  public void removeEntityNow(int entityId)
  {
    this.entities.remove(entityId);
  }

  /**
   * Removes a world immediately from the cache
   * @param worldId id
   */
  public void removeWorldNow(UUID worldId)
  {
    this.worldIds.remove(worldId);
  }

  /**
   * Gets an entity by id
   * <br><br>
   * This will trigger a GC cycle (on the current thread) if the entity is dead
   * @param entityId id
   * @return entity or null if the entity is approximated to be dead
   */
  public Entity get(int entityId)
  {
    final Entity entity = this.entities.get(entityId);
    if (entity != null && entity.isDead() /* since this isn't thread safe it's effectively an approximation */)
    {
      this.gcCycle();
      return null;
    }
    return entity;
  }

  /**
   * Gets an entity by id without triggering a GC cycle
   * <br><br>
   * This will not trigger a GC cycle if the entity is dead
   * @param entityId id
   * @return entity or null if the entity is approximated to be dead
   */
  public Entity getNoGC(int entityId)
  {
    final Entity entity = this.entities.get(entityId);
    if (entity == null || entity.isDead() /* since this isn't thread safe it's effectively an approximation */)
      return null;
    return entity;
  }

  /**
   * Submits a GC cycle to the GC executor
   */
  public void submitGCCycle()
  {
    this.gcExecutor.execute(this::gcCycle);
  }

  /**
   * Runs a GC cycle, removing all entities that are dead or in unloaded worlds
   */
  public void gcCycle()
  {
    if (DEBUG)
      LOGGER.info("Running GC cycle with " + this.entities.size() + " entities and " + this.worldIds.size() + " worlds cached");
    final Iterator<Map.Entry<Integer, Entity>> iterator = this.entities.entrySet().iterator();
    int removedCount = 0;
    while (iterator.hasNext())
    {
      final Entity entity = iterator.next().getValue();
      if (this.shouldGCEntity(entity))
      {
        iterator.remove();
        removedCount++;
      }
    }
    if (DEBUG)
      LOGGER.info("GC cycle removed " + removedCount + " entities");
  }

  /**
   * Clears the cache, removing all entities and world ids
   */
  public void clear()
  {
    this.entities.clear();
    this.worldIds.clear();
  }

  private boolean shouldGCEntity(Entity entity)
  {
    final World entityWorld = entity.getWorld();
    return entity.isDead() /* since this isn't thread safe it's effectively an approximation */ ||
      entityWorld == null ||
      !this.worldIds.contains(entityWorld.getUID());
  }
}