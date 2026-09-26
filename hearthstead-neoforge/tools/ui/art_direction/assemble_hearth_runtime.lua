-- Assemble the authored runtime Hearth planes into one editable Aseprite file.
local root = app.params["root"]
local out = app.params["out"]
local names = {
  "BACK_CAST_SHADOW",
  "FRAME_WOOD",
  "INSET_PAPER_AND_DRAWERS",
  "FOREGROUND_HARDWARE"
}

local sprite = Sprite(320, 220, ColorMode.RGB)
sprite.filename = out

for index, name in ipairs(names) do
  local layer = index == 1 and sprite.layers[1] or sprite:newLayer()
  layer.name = name
  local image = Image{fromFile=root .. "/hearth_runtime_" .. name .. ".png"}
  if index == 1 then
    sprite.cels[1].image = image
  else
    sprite:newCel(layer, 1, image, Point(0, 0))
  end
end

if app.fs.isFile(out) then os.remove(out) end
sprite:saveAs(out)
sprite:close()
