package org.dynmap.hdmap;

import org.dynmap.Color;
import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapManager;
import org.dynmap.utils.LightLevels;
import org.dynmap.utils.BlockStep;

public class ShadowHDLighting extends DefaultHDLighting {

    protected final int   defLightingTable[];  /* index=skylight level, value = 256 * scaling value */
    protected final int   lightscale[];   /* scale skylight level (light = lightscale[skylight] */
    protected final boolean night_and_day;    /* If true, render both day (prefix+'-day') and night (prefix) tiles */
    protected final boolean smooth;
    protected final boolean useWorldBrightnessTable;
    
    public ShadowHDLighting(DynmapCore core, ConfigurationNode configuration) {
        super(core, configuration);
        double shadowweight = configuration.getDouble("shadowstrength", 0.0);
        // See if we're using world's lighting table, or our own
        useWorldBrightnessTable = configuration.getBoolean("use-brightness-table", MapManager.mapman.useBrightnessTable());

        defLightingTable = new int[16];
        defLightingTable[15] = 256;
        /* Normal brightness weight in MC is a 20% relative dropoff per step */
        for(int i = 14; i >= 0; i--) {
            double v = defLightingTable[i+1] * (1.0 - (0.2 * shadowweight));
            defLightingTable[i] = (int)v;
            if(defLightingTable[i] > 256) defLightingTable[i] = 256;
            if(defLightingTable[i] < 0) defLightingTable[i] = 0;
        }
        int v = configuration.getInteger("ambientlight", -1);
        if(v < 0) v = 15;
        if(v > 15) v = 15;
        night_and_day = configuration.getBoolean("night-and-day", false);
        lightscale = new int[16];
        for(int i = 0; i < 16; i++) {
            if(i < (15-v))
                lightscale[i] = 0;
            else
                lightscale[i] = i - (15-v);
        }
        smooth = configuration.getBoolean("smooth-lighting", MapManager.mapman.getSmoothLighting());
    }
    
    private void    applySmoothLighting(HDPerspectiveState ps, HDShaderState ss, Color incolor, Color[] outcolor, int[] shadowscale) {
        int[] xyz = ps.getSubblockCoord();
        int scale = (int)ps.getScale();
        int mid = scale/2;
        BlockStep s1, s2;
        int w1, w2;
        /* Figure out which directions to look */
        switch(ps.getLastBlockStep()) {
        case X_MINUS:
        case X_PLUS:
        	// See which slice to use
            if(xyz[1] < mid) {
                s1 = BlockStep.Y_MINUS;
                w1 = mid - xyz[1];
            }
            else {
                s1 = BlockStep.Y_PLUS;
                w1 = xyz[1] - mid;
            }
            if(xyz[2] < mid) {
                s2 = BlockStep.Z_MINUS;
                w2 = mid - xyz[2];
            }
            else {
                s2 = BlockStep.Z_PLUS;
                w2 = xyz[2] - mid;
            }
            break;
        case Z_MINUS:
        case Z_PLUS:
            if(xyz[0] < mid) {
                s1 = BlockStep.X_MINUS;
                w1 = mid - xyz[0];
            }
            else {
                s1 = BlockStep.X_PLUS;
                w1 = xyz[0] - mid;
            }
            if(xyz[1] < mid) {
                s2 = BlockStep.Y_MINUS;
                w2 = mid - xyz[1];
            }
            else {
                s2 = BlockStep.Y_PLUS;
                w2 = xyz[1] - mid;
            }
            break;
        default:
            if(xyz[0] < mid) {
                s1 = BlockStep.X_MINUS;
                w1 = mid - xyz[0];
            }
            else {
                s1 = BlockStep.X_PLUS;
                w1 = xyz[0] - mid;
            }
            if(xyz[2] < mid) {
                s2 = BlockStep.Z_MINUS;
                w2 = mid - xyz[2];
            }
            else {
                s2 = BlockStep.Z_PLUS;
                w2 = xyz[2] - mid;
            }
            break;
        }
        // Order steps - first step should be one closer to midline (so we get current light, light from best face, and then light from step to side
        if (w1 > w2) {	// If first is more offset than second
        	int w = w1; w1 = w2; w2 = w;
        	BlockStep s = s1; s1 = s2; s2 = s;
        }
        /* Now get the 3 needed light levels */
        LightLevels skyemit0 = ps.getCachedLightLevels(0);
        LightLevels skyemit1 = ps.getCachedLightLevels(1);
        LightLevels skyemit2 = ps.getCachedLightLevels(2);
        ps.getLightLevelsAtOffsets(s1, s2, skyemit0, skyemit1, skyemit2);

        /* Get light levels */
        boolean day = true;
        for (int cidx = 0; cidx < outcolor.length; cidx++) {
            int ll0 = shadowscale[getLightLevel(skyemit0, day)];
            int ll1 = shadowscale[getLightLevel(skyemit1, day)];
            int ll2 = shadowscale[getLightLevel(skyemit2, day)];
            // Compute level based on linear interpolation along edges of triangle ll0 (current) ll1 (best edge) and ll2 (best corner)
            int ll;
            if ((ll0 == ll1) && (ll0 == ll2)) {	// If flat light, nothing to do
            	ll = ll0; 
        	}
            else {	// Linear of delta on each axis (
            	ll = (ll0 * scale) + (((ll1 - ll0) * w2) + (((ll2 - ll0) * w1))) / scale;
            }
            outcolor[cidx].setColor(incolor);
            if (ll < 256) {
                Color c = outcolor[cidx];
                c.setRGBA((c.getRed() * ll) >> 8, (c.getGreen() * ll) >> 8, 
                    (c.getBlue() * ll) >> 8, c.getAlpha());
            }
            day = false;
        }
    }
    
    private final int getLightLevel(final LightLevels ll, boolean useambient) {
        int lightlevel;
        /* If ambient light, adjust base lighting for it */
        if(useambient)
            lightlevel = lightscale[ll.sky];
        else
            lightlevel = ll.sky;
        /* If we're below max, see if emitted light helps */
        if(lightlevel < 15) {
            lightlevel = Math.max(ll.emitted, lightlevel);                                
        }
        return lightlevel;
    }
        
    /* Apply lighting to given pixel colors (1 outcolor if normal, 2 if night/day) */
    public void    applyLighting(HDPerspectiveState ps, HDShaderState ss, Color incolor, Color[] outcolor) {
        int[] shadowscale = null;
        if(smooth) {
            shadowscale = ss.getLightingTable();
            if (shadowscale == null) {
                shadowscale = defLightingTable;
            }
            applySmoothLighting(ps, ss, incolor, outcolor, shadowscale);
            checkGrayscale(outcolor);
            return;
        }
        LightLevels ll = null;
        int lightlevel = 15, lightlevel_day = 15;
        /* If processing for shadows, use sky light level as base lighting */
        if(defLightingTable != null) {
            shadowscale = ss.getLightingTable();
            if (shadowscale == null) {
                shadowscale = defLightingTable;
            }
            ll = ps.getCachedLightLevels(0);
            ps.getLightLevels(ll);
            lightlevel = lightlevel_day = ll.sky;
        }
        /* If ambient light, adjust base lighting for it */
        lightlevel = lightscale[lightlevel];
        /* If we're below max, see if emitted light helps */
        if((lightlevel < 15) || (lightlevel_day < 15)) {
            int emitted = ll.emitted;
            lightlevel = Math.max(emitted, lightlevel);                                
            lightlevel_day = Math.max(emitted, lightlevel_day);                                
        }
        /* Figure out our color, with lighting if needed */
        outcolor[0].setColor(incolor);
        if(lightlevel < 15) {
            shadowColor(outcolor[0], lightlevel, shadowscale);
        }
        if(outcolor.length > 1) {
            if(lightlevel_day == lightlevel) {
                outcolor[1].setColor(outcolor[0]);
            }
            else {
                outcolor[1].setColor(incolor);
                if(lightlevel_day < 15) {
                    shadowColor(outcolor[1], lightlevel_day, shadowscale);
                }
            }
        }
        checkGrayscale(outcolor);
    }

    private final void shadowColor(Color c, int lightlevel, int[] shadowscale) {
        int scale = shadowscale[lightlevel];
        if(scale < 256)
            c.setRGBA((c.getRed() * scale) >> 8, (c.getGreen() * scale) >> 8, 
                (c.getBlue() * scale) >> 8, c.getAlpha());
    }


    /* Test if night/day is enabled for this renderer */
    public boolean isNightAndDayEnabled() { return night_and_day; }
    
    /* Test if sky light level needed */
    public boolean isSkyLightLevelNeeded() { return true; }
    
    /* Test if emitted light level needed */
    public boolean isEmittedLightLevelNeeded() { return true; }    

    @Override
    public int[] getBrightnessTable(DynmapWorld world) {
        if (useWorldBrightnessTable) {
            return world.getBrightnessTable();
        }
        else {
            return null;
        }
    }
}
