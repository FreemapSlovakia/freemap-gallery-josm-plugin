# Freemap.sk Gallery JOSM Plugin

JOSM plugin to show pictures from the freemap.sk gallery.

## Building

```sh
ant clean jar
```

It compiles against `~/josm-tested.jar` by default; pass `-Djosm.jar=/path/to/josm.jar` to use another one.

The build also writes [plugin.txt](./plugin.txt) from the jar's manifest; JOSM reads update information from there, so commit it together with the jar after bumping `Plugin-Version` in [build.xml](./build.xml).

## Usage

Copy `freemap-gallery.jar` to the JOSM plugins directory (or add `https://github.com/FreemapSlovakia/fm-gallery-josm-plugin/raw/main/plugin.txt` as a plugin site), then run JOSM and enable it in _Edit / Preferences / Plugins_; search for _freemap-gallery_. Activate the layer with _Imagery / Freemap Gallery_. Images will be visible from the zoom 14 and will be loaded dynamically on map view change.

## Premium photos

To see premium photos as a premium Freemap.sk user, set `freemap.gallery.token` in _Edit / Preferences / Advanced Preferences_ to a Freemap.sk access token with the `gallery:read` scope. Without it, premium photos fail to load with HTTP 402.
