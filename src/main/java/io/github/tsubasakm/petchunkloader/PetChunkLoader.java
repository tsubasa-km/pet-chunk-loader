package io.github.tsubasakm.petchunkloader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the chunks around tamed wolves and cats force-loaded.
 * The forced chunks are written to the world folder, so they are force-loaded again
 * after a restart and the pets in them get picked up again.
 */
public final class PetChunkLoader implements ModInitializer {
    public static final String MOD_ID = "pet_chunk_loader";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "pet_chunk_loader.json";
    private static final int TICK_INTERVAL = 20;
    private static final int CHUNK_RADIUS = 1;

    // dimension id -> chunks this mod has force-loaded
    private static final Map<String, Set<Long>> FORCED_CHUNKS = new HashMap<>();
    private static boolean dirty;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(PetChunkLoader::restore);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % TICK_INTERVAL != 0) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                updateForcedChunks(level);
            }
            if (dirty) {
                save(server);
            }
        });
        // Do not release the chunks here: they have to stay forced across the restart
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save(server);
            FORCED_CHUNKS.clear();
        });
    }

    private static void updateForcedChunks(ServerLevel level) {
        Set<Long> wanted = new HashSet<>();
        for (TamableAnimal pet : level.getEntities(EntityTypeTest.forClass(TamableAnimal.class), PetChunkLoader::isChunkLoaderPet)) {
            ChunkPos center = ChunkPos.containing(pet.blockPosition());
            for (int x = center.x() - CHUNK_RADIUS; x <= center.x() + CHUNK_RADIUS; x++) {
                for (int z = center.z() - CHUNK_RADIUS; z <= center.z() + CHUNK_RADIUS; z++) {
                    wanted.add(ChunkPos.pack(x, z));
                }
            }
        }

        Set<Long> forced = FORCED_CHUNKS.computeIfAbsent(level.dimension().identifier().toString(), key -> new HashSet<>());
        Set<Long> before = Set.copyOf(forced);
        // A pet may be in a chunk whose entities have not been loaded yet (right after a restart),
        // so only release a chunk once the entities of it and its forced neighbours are loaded
        forced.removeIf(chunk -> {
            if (wanted.contains(chunk) || !neighbourhoodEntitiesLoaded(level, chunk, before)) {
                return false;
            }
            level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
            dirty = true;
            return true;
        });
        if (forced.addAll(wanted)) {
            dirty = true;
        }
        // Force every kept chunk again, not just the wanted ones: if someone removed one with /forceload
        // and the pets in it got unloaded, it has to load again for the pets to be found
        for (long chunk : forced) {
            level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
        }
    }

    private static boolean neighbourhoodEntitiesLoaded(ServerLevel level, long chunk, Set<Long> forced) {
        int cx = ChunkPos.getX(chunk);
        int cz = ChunkPos.getZ(chunk);
        for (int x = cx - CHUNK_RADIUS; x <= cx + CHUNK_RADIUS; x++) {
            for (int z = cz - CHUNK_RADIUS; z <= cz + CHUNK_RADIUS; z++) {
                long neighbour = ChunkPos.pack(x, z);
                if ((neighbour == chunk || forced.contains(neighbour)) && !level.areEntitiesLoaded(neighbour)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isChunkLoaderPet(TamableAnimal pet) {
        return pet.isAlive() && pet.isTame() && (pet instanceof Wolf || pet instanceof Cat);
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    private static void restore(MinecraftServer server) {
        FORCED_CHUNKS.clear();
        dirty = false;
        Path path = file(server);
        if (!Files.exists(path)) {
            return;
        }
        int count = 0;
        try {
            JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.getKey())));
                if (level == null) {
                    LOGGER.warn("Unknown dimension {} in {}, skipping", entry.getKey(), FILE_NAME);
                    continue;
                }
                Set<Long> forced = FORCED_CHUNKS.computeIfAbsent(entry.getKey(), key -> new HashSet<>());
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    JsonArray pos = element.getAsJsonArray();
                    int x = pos.get(0).getAsInt();
                    int z = pos.get(1).getAsInt();
                    level.setChunkForced(x, z, true);
                    forced.add(ChunkPos.pack(x, z));
                    count++;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to read {}", path, e);
            return;
        }
        LOGGER.info("Restored {} force-loaded pet chunks", count);
    }

    private static void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        FORCED_CHUNKS.forEach((dimension, chunks) -> {
            if (chunks.isEmpty()) {
                return;
            }
            JsonArray array = new JsonArray();
            chunks.stream().sorted().forEach(chunk -> {
                JsonArray pos = new JsonArray();
                pos.add(ChunkPos.getX(chunk));
                pos.add(ChunkPos.getZ(chunk));
                array.add(pos);
            });
            root.add(dimension, array);
        });
        Path path = file(server);
        Path temp = path.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", path, e);
        }
    }
}
