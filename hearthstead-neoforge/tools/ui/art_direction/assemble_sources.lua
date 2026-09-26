-- Assemble the authored PNG layers into canonical editable Aseprite sources.
-- Usage: aseprite --batch --script-param root=<repo> --script this_file.lua
local root = app.params["root"]
if not root then error("missing --script-param root=<repository>") end
root = root:gsub("\\", "/")

local specs = {
  hearth_ledger_427x240 = true,
  development_survey_427x240 = true,
  courier_dispatch_427x240 = true,
  plaque_workbench_427x240 = true,
}

local layers = {
  {"BACK/CAST_SHADOW", "back_cast_shadow"},
  {"FRAME_WOOD", "frame_wood"},
  {"INSET_PAPER_OR_LEATHER", "inset_paper_or_leather"},
  {"FOREGROUND_HARDWARE", "foreground_hardware"},
  {"TEXT_AND_ICONS", "text_and_icons"},
  {"INTERACTION_STATE", "interaction_state"},
}

for stem, _ in pairs(specs) do
  local folder = root .. "/tools/ui/art_direction/layers/" .. stem .. "/"
  local sprite = Sprite(427, 240, ColorMode.RGB)
  sprite.filename = root .. "/tools/ui/art_direction/sources/" .. stem .. ".aseprite"
  sprite.layers[1].name = layers[1][1]
  local first = Image{fromFile=folder .. layers[1][2] .. ".png"}
  sprite.cels[1].image = first
  for index = 2, #layers do
    local layer = sprite:newLayer()
    layer.name = layers[index][1]
    local image = Image{fromFile=folder .. layers[index][2] .. ".png"}
    sprite:newCel(layer, 1, image, Point(0, 0))
  end
  sprite:saveAs(sprite.filename)
  sprite:close()
end
