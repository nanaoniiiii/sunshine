package com.alkaidlab.sunshine.service;
/** Only handed to the display service owned by this host. */
oneway interface ISunshineDesktopCallback {
    void requestExit();
    void reportStatus(String status);
}
