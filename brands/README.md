# Brand packs

A brand pack makes Kabina look like it belongs to *your* car: the maker's logo on the intro and the
home screen, a 3D model of your car on the Vehicle screen, a photo of your cabin on the Lighting screen.

Brand packs live in `brands/<name>/` and are **git-ignored** — car makers' names, logos and photos are
trademarks or copyrighted, so keep them on your machine and don't publish them.

```
brands/mycar/
├── res/
│   ├── drawable-nodpi/
│   │   ├── logo.png          square-ish emblem, transparent background (~512 px)       [replaces default]
│   │   ├── wordmark.png      white text logo, wide and short (~1000 × 90 px)            [optional]
│   │   ├── ic_launcher.png   launcher icon (192 px)                                     [replaces default]
│   │   └── cabin.jpg         cabin seen from above, front of the car on the RIGHT      [optional]
│   ├── values/brand.xml      app_name, model_credit, color_names (8 items)              [optional]
│   └── values-bg/…           translated overrides, e.g. color_subs                      [optional]
└── assets/
    ├── car.bin               3D model baked with tools/bake_car.py                        [optional]
    └── livery.png            decal atlas projected on the paint (see CarGLView)           [optional]
```

Without `wordmark.png` the app name is shown in spaced capitals. Without `cabin.jpg` the Lighting screen
shows four light pools on a dark panel. Without `car.bin` the Vehicle screen shows a glowing wireframe van.

`res/values/brand.xml` example:

```xml
<resources>
    <string name="app_name" translatable="false">My Car</string>
    <string name="model_credit" translatable="false">3D model: “…” by … · CC BY 4.0</string>
    <string-array name="color_names" translatable="false">
        <item>OCEAN</item> <item>ICE</item> <item>RUBY</item> <item>EMBER</item>
        <item>SAHARA</item> <item>NEBULA</item> <item>AURORA</item> <item>NEON ROSE</item>
    </string-array>
</resources>
```

If your 3D model's licence requires attribution (e.g. CC BY from Sketchfab), put it in `model_credit` —
it is shown under the model and on the *All apps* screen.

## 3D model

```sh
Blender -b -P tools/bake_car.py -- my_car.glb brands/mycar/assets/car.bin 60000
```

Materials are classified by name (paint/body, glass/window/lens, chrome/rim/metal, …) — see the script.

## Build

```sh
./gradlew assembleBrandedDebug -Pbrand=mycar
```
