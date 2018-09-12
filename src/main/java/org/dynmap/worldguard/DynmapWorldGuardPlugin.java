package org.dynmap.worldguard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.BlockVector2D;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.domains.PlayerDomain;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionType;

public class DynmapWorldGuardPlugin extends JavaPlugin {
    private static Logger log;
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    public static final String BOOST_FLAG = "dynmap-boost";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    WorldGuardPlugin wg;
    BooleanFlag boost_flag;
    int updatesPerTick = 20;

    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Map<String, AreaStyle> cuswildstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop; 
    int maxdepth;

    @Override
    public void onLoad() {
        log = this.getLogger();
        this.registerCustomFlags();
    }
    
    private static class AreaStyle {
        String strokecolor;
        String unownedstrokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", def.unownedstrokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            label = cfg.getString(path+".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", "#00FF00");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();
	private WorldGuardPlatform platform;
	public WorldGuardPlatform p;

    private String formatInfoWindow(ProtectedRegion region, AreaMarker m) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replace("%regionname%", m.getLabel());
		v = v.replace("%playerowners%", region.getOwners().toPlayersString());
        v = v.replace("%groupowners%", region.getOwners().toGroupsString());
        v = v.replace("%playermembers%", region.getMembers().toPlayersString());
        v = v.replace("%groupmembers%", region.getMembers().toGroupsString());
        if(region.getParent() != null)
            v = v.replace("%parent%", region.getParent().getId());
        else
            v = v.replace("%parent%", "");
        v = v.replace("%priority%", String.valueOf(region.getPriority()));
        Map<Flag<?>, Object> map = region.getFlags();
        String flgs = "";
        for(Flag<?> f : map.keySet()) {
            flgs += f.getName() + ": " + map.get(f).toString() + "<br/>";
        }
        v = v.replace("%flags%", flgs);
        return v;
    }
    
    private boolean isVisible(String id, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((visible.contains(id) == false) && (visible.contains("world:" + worldname) == false) &&
                    (visible.contains(worldname + "/" + id) == false)) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + id))
                return false;
        }
        return true;
    }
    
    private void addStyle(String resid, String worldid, AreaMarker m, ProtectedRegion region) {
        AreaStyle as = cusstyle.get(worldid + "/" + resid);
        if(as == null) {
            as = cusstyle.get(resid);
        }
        if(as == null) {    /* Check for wildcard style matches */
            for(String wc : cuswildstyle.keySet()) {
                String[] tok = wc.split("\\|");
                if((tok.length == 1) && resid.startsWith(tok[0]))
                    as = cuswildstyle.get(wc);
                else if((tok.length >= 2) && resid.startsWith(tok[0]) && resid.endsWith(tok[1]))
                    as = cuswildstyle.get(wc);
            }
        }
        if(as == null) {    /* Check for owner style matches */
            if(ownerstyle.isEmpty() != true) {
                DefaultDomain dd = region.getOwners();
                PlayerDomain pd = dd.getPlayerDomain();
                if(pd != null) {
                    for(String p : pd.getPlayers()) {
                        if(as == null) {
                            as = ownerstyle.get(p.toLowerCase());
                            if (as != null) break;
                        }
                    }
                    if (as == null) {
                        for(UUID uuid : pd.getUniqueIds()) {
                            as = ownerstyle.get(uuid.toString());
                            if (as != null) break;
                        }
                    }
                    if (as == null) {
                    	for(String p : pd.getPlayers()) {
                            if (p != null) {
                                as = ownerstyle.get(p.toLowerCase());
                                if (as != null) break;
                            }
                        }
                    }
                }
                if (as == null) {
                    Set<String> grp = dd.getGroups();
                    if(grp != null) {
                        for(String p : grp) {
                            as = ownerstyle.get(p.toLowerCase());
                            if (as != null) break;
                        }
                    }
                }
            }
        }
        if(as == null)
            as = defstyle;

        boolean unowned = false;
        if((region.getOwners().getPlayers().size() == 0) &&
                (region.getOwners().getUniqueIds().size() == 0 )&&
                (region.getOwners().getGroups().size() == 0)) {
            unowned = true;
        }
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if(unowned)
                sc = Integer.parseInt(as.unownedstrokecolor.substring(1), 16);
            else
                sc = Integer.parseInt(as.strokecolor.substring(1), 16);
           fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
        if (boost_flag != null) {
            Boolean b = region.getFlag(boost_flag);
            m.setBoostFlag((b == null)?false:b.booleanValue());
        }
    }
        
    /* Handle specific region */
    private void handleRegion(World world, ProtectedRegion region, Map<String, AreaMarker> newmap) {
        String name = region.getId();
        /* Make first letter uppercase */
        name = name.substring(0, 1).toUpperCase() + name.substring(1);
        double[] x = null;
        double[] z = null;
                
        /* Handle areas */
        if(isVisible(region.getId(), world.getName())) {
            String id = region.getId();
            RegionType tn = region.getType();
            BlockVector l0 = region.getMinimumPoint();
            BlockVector l1 = region.getMaximumPoint();

            if(tn == RegionType.CUBOID) { /* Cubiod region? */
                /* Make outline */
                x = new double[4];
                z = new double[4];
                x[0] = l0.getX(); z[0] = l0.getZ();
                x[1] = l0.getX(); z[1] = l1.getZ()+1.0;
                x[2] = l1.getX() + 1.0; z[2] = l1.getZ()+1.0;
                x[3] = l1.getX() + 1.0; z[3] = l0.getZ();
            }
            else if(tn == RegionType.POLYGON) {
                ProtectedPolygonalRegion ppr = (ProtectedPolygonalRegion)region;
                List<BlockVector2D> points = ppr.getPoints();
                x = new double[points.size()];
                z = new double[points.size()];
                for(int i = 0; i < points.size(); i++) {
                    BlockVector2D pt = points.get(i);
                    x[i] = pt.getX(); z[i] = pt.getZ();
                }
            }
            else {  /* Unsupported type */
                return;
            }
            String markerid = world.getName() + "_" + id;
            AreaMarker m = resareas.remove(markerid); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
                if(m == null)
                    return;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(l1.getY()+1.0, l0.getY());
            }            
            /* Set line and fill properties */
            addStyle(id, world.getName(), m, region);

            /* Build popup */
            String desc = formatInfoWindow(region, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }
    
    private class UpdateJob implements Runnable {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
        List<World> worldsToDo = null;
        List<ProtectedRegion> regionsToDo = null;
        World curworld = null;
        
        public void run() {
            if (stop) {
                return;
            }
            // If worlds list isn't primed, prime it
            if (worldsToDo == null) {
            	List<org.bukkit.World> w = Bukkit.getWorlds();
                worldsToDo = new ArrayList<World>();
                for (org.bukkit.World wrld : w) {
                	worldsToDo.add(platform.getWorldByName(wrld.getName()));
                }
            }
            while (regionsToDo == null) {  // No pending regions for world
                if (worldsToDo.isEmpty()) { // No more worlds?
                    /* Now, review old map - anything left is gone */
                    for(AreaMarker oldm : resareas.values()) {
                        oldm.deleteMarker();
                    }
                    /* And replace with new map */
                    resareas = newmap;
                    // Set up for next update (new job)
                    getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, new UpdateJob(), updperiod);
                    return;
                }
                else {
                    curworld = worldsToDo.remove(0);
                    RegionContainer rc = platform.getRegionContainer();
                    RegionManager rm = rc.get(curworld); /* Get region manager for world */
                    if(rm != null) {
                        Map<String,ProtectedRegion> regions = rm.getRegions();  /* Get all the regions */
                        if ((regions != null) && (regions.isEmpty() == false)) {
                            regionsToDo = new ArrayList<ProtectedRegion>(regions.values());
                        }
                    }
                }
            }
            /* Now, process up to limit regions */
            for (int i = 0; i < updatesPerTick; i++) {
                if (regionsToDo.isEmpty()) {
                    regionsToDo = null;
                    break;
                }
                ProtectedRegion pr = regionsToDo.remove(regionsToDo.size()-1);
                int depth = 1;
                ProtectedRegion p = pr;
                while(p.getParent() != null) {
                    depth++;
                    p = p.getParent();
                }
                if(depth > maxdepth)
                    continue;
                handleRegion(curworld, pr, newmap);
            }
            // Tick next step in the job
            getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, this, 1);
        }
    }

    private class OurServerListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("WorldGuard")) {
                if(dynmap.isEnabled() && wg.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get WorldGuard */
        Plugin p = pm.getPlugin("WorldGuard");
        if(p == null) {
            severe("Cannot find WorldGuard!");
            return;
        }
        wg = (WorldGuardPlugin)p;
        
        platform = WorldGuard.getInstance().getPlatform();
        
        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
        
        /* If both enabled, activate */
        if(dynmap.isEnabled() && wg.isEnabled())
            activate();
        /* Start up metrics */
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {
            
        }
    }
    
    private void registerCustomFlags() {
        try {
            BooleanFlag bf = new BooleanFlag(BOOST_FLAG);
            FlagRegistry fr = WorldGuard.getInstance().getFlagRegistry();
        	fr.register(bf);
            boost_flag = bf;
        } catch (Exception x) {
        	log.info("Error registering flag - " + x.getMessage());
        }
        if (boost_flag == null) {
            log.info("Custom flag '" + BOOST_FLAG + "' not registered");
        }
    }
    
    private boolean reload = false;
    
    private void activate() {        
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        if(reload) {
            this.reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("worldguard.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("worldguard.markerset", cfg.getString("layer.name", "WorldGuard"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "WorldGuard"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        maxdepth = cfg.getInt("maxdepth", 16);
        updatesPerTick = cfg.getInt("updates-per-tick", 20);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ownerstyle = new HashMap<String, AreaStyle>();
        cuswildstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                if(id.indexOf('|') >= 0)
                    cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
                else
                    cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        sect = cfg.getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleregions");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new UpdateJob(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
