package com.hearthstead.settlement.guard;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Settlement-local memberships and shared orders, including unloaded residents. */
public final class BannerTeamBook {
    public enum Order { MOVE, HOLD, ATTACK, FOLLOW }
    public record Command(Order order, BlockPos target, UUID enemy, UUID issuer, UUID id) {
        public Command(Order order,BlockPos target,UUID enemy,UUID issuer){this(order,target,enemy,issuer,UUID.randomUUID());}
    }
    private final Map<UUID,Integer> members = new LinkedHashMap<>();
    private final Map<Integer,UUID> leaders = new LinkedHashMap<>();
    private final Map<Integer,Command> commands = new LinkedHashMap<>();
    private boolean quarantined;
    public boolean quarantined() { return quarantined; }
    public Integer color(UUID member) { return quarantined ? null : members.get(member); }
    public Map<UUID,Integer> members() { return Map.copyOf(members); }
    public UUID leader(int color) { return leaders.get(color); }
    public Command command(int color) { return quarantined ? null : commands.get(color); }
    public boolean assign(UUID member, int color) {
        if (quarantined || member == null || color < 0 || color > 15
            || members.size() >= 256 && !members.containsKey(member)) return false;
        members.put(member, color); return true;
    }
    public boolean claim(int color, UUID leader) {
        if (quarantined || color < 0 || color > 15 || leader == null) return false;
        leaders.put(color,leader); return true;
    }
    public void issue(int color, Command command) { if (!quarantined) commands.put(color,command); }
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version",1); tag.putBoolean("Quarantined",quarantined);
        ListTag roster = new ListTag();
        members.forEach((id,color) -> { CompoundTag row=new CompoundTag(); row.putUUID("Id",id); row.putInt("Color",color); roster.add(row); });
        tag.put("Members",roster); ListTag teams = new ListTag();
        leaders.forEach((color,leader) -> {
            CompoundTag row=new CompoundTag(); row.putInt("Color",color); row.putUUID("Leader",leader);
            Command command=commands.get(color);
            if(command!=null) { row.putString("Order",command.order().name()); row.putUUID("Issuer",command.issuer()); row.putUUID("CommandId",command.id());
                if(command.target()!=null) row.putLong("Target",command.target().asLong());
                if(command.enemy()!=null) row.putUUID("Enemy",command.enemy()); }
            teams.add(row);
        }); tag.put("Teams",teams); return tag;
    }
    public static BannerTeamBook readNbt(CompoundTag tag) {
        BannerTeamBook book=new BannerTeamBook();
        if(tag==null) return book;
        if(tag.getInt("Version")!=1 || !tag.contains("Members",Tag.TAG_LIST)
            || !tag.contains("Teams",Tag.TAG_LIST) || tag.getBoolean("Quarantined")) return invalid();
        ListTag roster=(ListTag)tag.get("Members"), teams=(ListTag)tag.get("Teams");
        if ((!roster.isEmpty() && roster.getElementType()!=Tag.TAG_COMPOUND)
            || (!teams.isEmpty() && teams.getElementType()!=Tag.TAG_COMPOUND)) return invalid();
        if(roster.size()>256 || teams.size()>16) return invalid();
        for(int i=0;i<roster.size();i++) { CompoundTag row=roster.getCompound(i); int color=row.getInt("Color");
            if(!row.hasUUID("Id") || !row.contains("Color",Tag.TAG_INT) || color<0 || color>15
                || book.members.putIfAbsent(row.getUUID("Id"),color)!=null) return invalid(); }
        for(int i=0;i<teams.size();i++) { CompoundTag row=teams.getCompound(i); int color=row.getInt("Color");
            if(!row.hasUUID("Leader") || !row.contains("Color",Tag.TAG_INT) || color<0 || color>15
                || book.leaders.putIfAbsent(color,row.getUUID("Leader"))!=null) return invalid();
            if(row.contains("Order")) {
                Order order; try { order=Order.valueOf(row.getString("Order")); } catch(IllegalArgumentException ex) { return invalid(); }
                if(!row.hasUUID("Issuer") || order!=Order.FOLLOW && !row.contains("Target",Tag.TAG_LONG)) return invalid();
                book.commands.put(color,new Command(order,row.contains("Target",Tag.TAG_LONG)?BlockPos.of(row.getLong("Target")):null,
                    row.hasUUID("Enemy")?row.getUUID("Enemy"):null,row.getUUID("Issuer"),row.hasUUID("CommandId")?row.getUUID("CommandId"):UUID.randomUUID()));
            }
        } return book;
    }
    private static BannerTeamBook invalid() { BannerTeamBook result=new BannerTeamBook(); result.quarantined=true; return result; }
}
