-- Assemble selected Concept #2 states as six-layer editable Aseprite files.
local root = app.params["root"]
if not root then error("missing root") end
root = root:gsub("\\", "/")
local states = {"hearth_carpenter_a_normal_427x240", "hearth_carpenter_b_hover_427x240", "hearth_carpenter_c_blocked_427x240"}
local layers = {
  {"BACK/CAST_SHADOW", "back_cast_shadow"}, {"FRAME_WOOD", "frame_wood"},
  {"INSET_PAPER_OR_LEATHER", "inset_paper_or_leather"}, {"FOREGROUND_HARDWARE", "foreground_hardware"},
  {"TEXT_AND_ICONS", "text_and_icons"}, {"INTERACTION_STATE", "interaction_state"},
}
for _, stem in ipairs(states) do
  local folder = root .. "/tools/ui/art_direction/carpenter_desk_layers/" .. stem .. "/"
  local sprite = Sprite(427, 240, ColorMode.RGB)
  sprite.filename = root .. "/tools/ui/art_direction/sources/" .. stem .. ".aseprite"
  sprite.layers[1].name = layers[1][1]
  sprite.cels[1].image = Image{fromFile=folder .. layers[1][2] .. ".png"}
  for i = 2, #layers do
    local target = sprite:newLayer(); target.name = layers[i][1]
    sprite:newCel(target, 1, Image{fromFile=folder .. layers[i][2] .. ".png"}, Point(0, 0))
  end
  sprite:saveAs(sprite.filename); sprite:close()
end
