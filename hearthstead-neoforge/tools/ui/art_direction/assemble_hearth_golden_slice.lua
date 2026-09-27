-- Assemble the Hearth approval states into real editable Aseprite sources.
-- Usage: aseprite --batch --script-param root=<repo> --script this_file.lua
local root = app.params["root"]
if not root then error("missing --script-param root=<repository>") end
root = root:gsub("\\", "/")

local states = {"hearth_a_normal_427x240", "hearth_b_hover_427x240", "hearth_c_blocked_427x240"}
local layers = {
  {"BACK/CAST_SHADOW", "back_cast_shadow"},
  {"FRAME_WOOD", "frame_wood"},
  {"INSET_PAPER_OR_LEATHER", "inset_paper_or_leather"},
  {"FOREGROUND_HARDWARE", "foreground_hardware"},
  {"TEXT_AND_ICONS", "text_and_icons"},
  {"INTERACTION_STATE", "interaction_state"},
}

for _, stem in ipairs(states) do
  local folder = root .. "/tools/ui/art_direction/hearth_golden_layers/" .. stem .. "/"
  local sprite = Sprite(427, 240, ColorMode.RGB)
  sprite.filename = root .. "/tools/ui/art_direction/sources/" .. stem .. ".aseprite"
  sprite.layers[1].name = layers[1][1]
  sprite.cels[1].image = Image{fromFile=folder .. layers[1][2] .. ".png"}
  for index = 2, #layers do
    local target = sprite:newLayer()
    target.name = layers[index][1]
    local source = Image{fromFile=folder .. layers[index][2] .. ".png"}
    sprite:newCel(target, 1, source, Point(0, 0))
  end
  sprite:saveAs(sprite.filename)
  sprite:close()
end
