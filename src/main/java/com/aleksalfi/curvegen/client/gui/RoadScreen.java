package com.aleksalfi.curvegen.client.gui;

/** Screens that show road network data and must refresh when a sync arrives. */
public interface RoadScreen {
    void onNetworkChanged();
}
