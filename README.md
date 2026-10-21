# Freemap.sk Gallery JOSM Plugin

JOSM plugin to show pictures from the freemap.sk gallery.

## Building

```sh
ant clean jar
```

It compiles against `~/josm-tested.jar` by default; pass `-Djosm.jar=/path/to/josm.jar` to use another one.

After changing the version in [build.xml](./build.xml), copy the manifest of the built jar into [plugin.txt](./plugin.txt) (each line indented with a tab), as JOSM reads update information from there.

## Usage

Copy `freemap-gallery.jar` to the JOSM plugins directory (or add `https://github.com/FreemapSlovakia/fm-gallery-josm-plugin/raw/main/plugin.txt` as a plugin site), then run JOSM and enable it in _Edit / Preferences / Plugins_; search for _freemap-gallery_. Activate the layer with _Imagery / Freemap Gallery_. Images will be visible from the zoom 14 and will be loaded dynamically on map view change.
