local root = app.params["root"]
if not root then error("missing root") end
root = root:gsub("\\", "/")
local specs = {
  {"hearth_clean_a_normal_427x240",427,240}, {"hearth_clean_b_hover_427x240",427,240},
  {"hearth_clean_c_blocked_427x240",427,240}, {"hearth_clean_compact_320x240",320,240},
}
local layers = {
  {"CLEAN_DESK_BASE","clean_desk_base"}, {"RESPONSIVE_DRAWER_ADAPTATION","responsive_drawer_adaptation"},
  {"SLOTS_AND_ITEMS","slots_and_items"}, {"TEXT_AND_VALUES","text_and_values"},
  {"FOREGROUND_HARDWARE","foreground_hardware"}, {"INTERACTION_STATE","interaction_state"},
}
for _, spec in ipairs(specs) do
  local stem,w,h=spec[1],spec[2],spec[3]
  local folder=root.."/tools/ui/art_direction/hearth_clean_composite_layers/"..stem.."/"
  local sprite=Sprite(w,h,ColorMode.RGB)
  sprite.filename=root.."/tools/ui/art_direction/sources/"..stem..".aseprite"
  sprite.layers[1].name=layers[1][1]; sprite.cels[1].image=Image{fromFile=folder..layers[1][2]..".png"}
  for i=2,#layers do local target=sprite:newLayer(); target.name=layers[i][1]
    sprite:newCel(target,1,Image{fromFile=folder..layers[i][2]..".png"},Point(0,0)) end
  sprite:saveAs(sprite.filename); sprite:close()
end
