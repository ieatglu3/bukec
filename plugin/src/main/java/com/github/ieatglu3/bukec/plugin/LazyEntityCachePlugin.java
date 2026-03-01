package com.github.ieatglu3.bukec.plugin;

import com.github.ieatglu3.bukec.LazyEntityCache;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plugin that provides a global instance of {@link LazyEntityCache}
 * <br>
 * Use {@link #getCache()} or {@link #getCacheOrNull()} to access the cache instance
 */
public final class LazyEntityCachePlugin extends JavaPlugin
{

  private static final AtomicReference<LazyEntityCache> ENTITY_CACHE = new AtomicReference<>(null);

  /**
   * Provides access to the {@link LazyEntityCache} instance
   * @return the {@link LazyEntityCache} instance
   * @throws IllegalStateException if the {@link LazyEntityCache} is not initialized
   */
  public static LazyEntityCache getCache()
  {
    LazyEntityCache cache = ENTITY_CACHE.get();
    if (cache == null)
      throw new IllegalStateException("LazyEntityCache is not initialized");
    return cache;
  }

  /**
   * Provides access to the {@link LazyEntityCache} instance
   * @return the {@link LazyEntityCache} instance, or null if it is not initialized
   */
  public static LazyEntityCache getCacheOrNull()
  {
    return ENTITY_CACHE.get();
  }

  @Override
  public void onEnable()
  {
    final LazyEntityCache cache = LazyEntityCache.create();
    cache.link(this);
    cache.startGC();
    ENTITY_CACHE.set(cache);
    this.getLogger().info("initialized and linked to plugin");
  }

  @Override
  public void onDisable()
  {
    final LazyEntityCache cache = ENTITY_CACHE.getAndSet(null);
    if (cache != null)
    {
      cache.unlink();
      try
      {
        if (!cache.shutdownGCExecutor(Duration.ofSeconds(5)))
          this.getLogger().warning("Failed to stop GC thread within timeout");

        this.getLogger().info("Stopped GC thread");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        this.getLogger().warning("Interrupted while stopping GC thread");
      }
    }
    this.getLogger().info("unlinked");
  }
}