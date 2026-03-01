# bukkit-entity-cache (bukec) (1.8 - 1.21)

A lightweight, thread-safe entity cache that maps entities by their integer ID without reflection on **Bukkit/Spigot based Minecraft servers**.

> **Note: This library was primarily designed for high-performance packet related plugins that need to get server-side entities by their integer ID without using reflection.**

---
## Usage

### Creating and linking the cache

```java
import com.github.ieatglu3.bukec.LazyEntityCache;

public class MyPlugin extends JavaPlugin {

    private LazyEntityCache entityCache;

    @Override
    public void onEnable() {
        // Create cache with default GC interval (1 minute)
        entityCache = LazyEntityCache.create();
        entityCache.link(this);
        // start gc
        entityCache.startGC();
    }

    @Override
    public void onDisable() {
        entityCache.unlink();
        
        // if the GC executor is shared, be wary when shutting it down
        // alternatively, you can call stopGC to stop the GC task without shutting down the executor
        
        /* entityCache.stopGC(); */
      
        entityCache.shutdownGCExecutor();
    }
}
```

---
### Retrieving entities

```java
// Get an entity by its entity ID which triggers a GC cycle (on the current thread) if the entity is dead
// returns null if the entity is not cached or has been removed by GC
Entity entity = entityCache.get(entityId);

// Get an entity without triggering any GC cycles
// returns null if the entity is not cached or has been removed by GC
Entity entity = entityCache.getNoGC(entityId);
```

---
### GC Configuration

Interval 
```java
// GC every 30 seconds
LazyEntityCache cache = LazyEntityCache.create(Duration.ofSeconds(30));
```

Executor
```java
ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
LazyEntityCache cache = LazyEntityCache.create(executor, Duration.ofMinutes(2));
```
---

## Installation

### Gradle (Kotlin DSL)
Add the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.ieatglu3:bukec:1.0.0")
}
```

### Gradle (Groovy DSL)
```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.ieatglu3:bukec:1.0.0'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.ieatglu3</groupId>
    <artifactId>bukec</artifactId>
    <version>1.0.0</version>
</dependency>
```

---
## Requirements

- **Java 8** or higher
- **Bukkit/Spigot API 1.8.8+**
---

<div align="center">

https://github.com/ieatglu3

</div>
