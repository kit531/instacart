# Insta Cart (Fabric, Minecraft 26.2)

Client-side mod. One configurable key (default **R**, change it in *Options > Controls > Key Binds > Insta Cart*):

1. Checks your **hotbar** for: a bow with **Flame**, any rail, a **TNT minecart** (plus arrows).
2. Checks that the block you are aiming at is in reach and a rail fits there (or that you are already aiming at a rail).
3. Places the rail, places the TNT minecart on it, aims at the cart, draws the bow and releases.
   A flaming arrow detonates a TNT minecart instantly. Your previously selected slot is restored afterwards.

Missing items / no room are reported in chat.

Config: `config/instacart.properties` (created on first launch)

- `draw_ticks` (3-20, default 20): bow draw time. 20 = full power; lower is faster but weaker.
- `auto_aim` (default true): turn the camera to the cart while drawing.

## Build

Requires JDK 25.

    ./gradlew build

The jar is in `build/libs/instacart-1.0.0.jar`. Put it in `mods/` together with Fabric API 0.161.0+26.2 and Fabric Loader 0.19.3 or newer.
Pushing to GitHub also builds it via `.github/workflows/build.yml` (download from the Actions artifacts).

Note: the explosion is big. Keep your distance, and check that the server you play on allows automation like this.
