package @@PACKAGE@@;

import j2a.core.Config;
import j2a.core.J2meActivity;

/**
 * Entry point of the port. The MIDlet is started by J2meActivity using
 * assets/j2a/port.properties; override onConfigLoaded to tweak it from code.
 */
public class MainActivity extends J2meActivity {

    @Override
    protected void onConfigLoaded(Config cfg) {
        // Examples:
        // cfg.fpsLimit = 30;          // slow down games that run too fast
        // cfg.scaleMode = "integer";  // pixel-perfect scaling
        // cfg.keypad = "minimal";     // d-pad + OK only
    }
}
