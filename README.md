# Pet Chunk Loader

A small server-side Fabric mod that keeps the chunks around your tamed wolves and cats loaded — and keeps them loaded after a server restart.

## Features

- Every second, finds tamed wolves and cats and force-loads the 3×3 chunks around each of them.
- The chunk area follows the pet as it moves, and is released when the pet is gone (dies, is untamed, or leaves).
- The force-loaded chunks are saved to `pet_chunk_loader.json` in the world folder and force-loaded again on startup, so pets that were left somewhere keep being loaded after a restart, even with nobody online.
- A chunk is only released once the entities in it have been loaded, so pets are not dropped right after a restart.
- If a pet's chunk is removed with `/forceload remove`, it is force-loaded again within a second.
- Works in every dimension.

## Installation

- Requires [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api).
- Install on the server. Players do not need it.
- Also works in singleplayer.

You can check which chunks are loaded with `/forceload query`.

## Notes

- Pets keep loading chunks wherever they are, which costs some server performance. Make pets sit at home, or untame them, if you have many of them spread around.
- To clear every chunk this mod has loaded, remove the mod, delete `pet_chunk_loader.json` from the world folder, and run `/forceload remove all` in each dimension.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runServer` starts a test server in `run/`.

## License

MIT
