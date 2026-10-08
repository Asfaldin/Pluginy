package elo.mainplugins.guilds;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Jedna gildia. id = tag małymi literami (unikalny). Zmieniana w miejscu, zapisywana przez GuildStore. */
public final class Guild {

    public enum Role { LEADER, OFFICER, MEMBER }

    public final String id;
    public String tag;
    public String name;
    public UUID leader;
    public final Set<UUID> officers = new LinkedHashSet<>();
    /** Wszyscy członkowie razem z liderem i oficerami. */
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Set<String> allies = new LinkedHashSet<>();
    /** Dom gildii: świat;x;y;z;yaw;pitch albo null. */
    public String home;
    public double bank;
    public long created;

    public Guild(String tag, String name, UUID leader) {
        this.id = tag.toLowerCase();
        this.tag = tag;
        this.name = name;
        this.leader = leader;
        this.members.add(leader);
        this.created = System.currentTimeMillis();
    }

    public Role role(UUID player) {
        if (player.equals(leader)) return Role.LEADER;
        if (officers.contains(player)) return Role.OFFICER;
        return Role.MEMBER;
    }

    /** Lider i oficerowie zarządzają członkami i domem; usuwać oficerów może tylko lider. */
    public boolean canManage(UUID player) {
        return role(player) != Role.MEMBER;
    }

    public boolean canKick(UUID by, UUID target) {
        if (by.equals(target) || target.equals(leader)) return false;
        Role r = role(by);
        if (r == Role.LEADER) return true;
        return r == Role.OFFICER && role(target) == Role.MEMBER;
    }
}
