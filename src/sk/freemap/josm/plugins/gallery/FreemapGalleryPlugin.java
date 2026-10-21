package sk.freemap.josm.plugins.gallery;

import java.awt.event.ActionEvent;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.layer.MainLayerManager;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class FreemapGalleryPlugin extends Plugin {

    public FreemapGalleryPlugin(PluginInformation info) {
        super(info);

        MainMenu.add(MainApplication.getMenu().imageryMenu, new AddGalleryLayerAction());
    }

    private static class AddGalleryLayerAction extends JosmAction {

        AddGalleryLayerAction() {
            super("Freemap Gallery", "icon.svg", "Add dynamic Freemap.sk photos layer (from zoom 14)", null, false,
                    null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            MainLayerManager layerManager = MainApplication.getLayerManager();

            List<GalleryLayer> existing = layerManager.getLayersOfType(GalleryLayer.class);

            if (existing.isEmpty()) {
                layerManager.addLayer(new GalleryLayer());
            } else {
                layerManager.setActiveLayer(existing.get(0));
            }
        }
    }
}
