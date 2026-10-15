package cn.mx.modernkb.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class PlayerStore {
    public record Preference(String profile, boolean excluded) {}
    private static final Preference DEFAULT = new Preference("default",false);
    private final Path file;
    private volatile Map<UUID,Preference> preferences;
    public PlayerStore(Path file) {
        this.file=file;
        var values=new HashMap<UUID,Preference>();
        if(Files.exists(file)) {
            var yaml=YamlFiles.load(file);
            var section=yaml.getConfigurationSection("players");
            if(yaml.contains("players") && section==null) throw new IllegalArgumentException("Invalid players.yml");
            if(section!=null) for(String key:section.getKeys(false)) {
                var player=section.getConfigurationSection(key);
                if(player==null || !player.isString("profile")) throw new IllegalArgumentException("Invalid player "+key);
                values.put(UUID.fromString(key),new Preference(player.getString("profile"),KnockbackProfile.bool(player,"excluded",false)));
            }
        }
        preferences=Map.copyOf(values);
    }
    public Preference get(UUID id) { return preferences.getOrDefault(id,DEFAULT); }
    public Set<String> profileNames() {
        var names = new HashSet<String>();
        preferences.values().forEach(value -> names.add(value.profile));
        return Set.copyOf(names);
    }
    public synchronized void profile(UUID id,String name) { update(id,new Preference(name,get(id).excluded)); }
    public synchronized void excluded(UUID id,boolean excluded) { update(id,new Preference(get(id).profile,excluded)); }
    private void update(UUID id,Preference value) {
        var updated=new HashMap<>(preferences);
        if(value.equals(DEFAULT)) updated.remove(id); else updated.put(id,value);
        var yaml=new YamlConfiguration();
        updated.forEach((uuid,pref)->{
            yaml.set("players."+uuid+".profile",pref.profile);
            yaml.set("players."+uuid+".excluded",pref.excluded);
        });
        try { YamlFiles.save(file,yaml); }
        catch(IOException e) { throw new IllegalStateException("Player settings NOT saved: "+file,e); }
        preferences=Map.copyOf(updated);
    }
}
