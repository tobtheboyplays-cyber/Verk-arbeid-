-- Assemble the Surveyor's Roll-Top planes into one editable Aseprite source.
local root = app.params["root"]
local out = app.params["out"]
local names = {
  "BACK_CAST_SHADOW",
  "FRAME_ROLLTOP_WOOD",
  "INSET_SCROLL_TRAYS",
  "FOREGROUND_INSTRUMENTS"
}

local sprite = Sprite(320, 220, ColorMode.RGB)
sprite.filename = out

for index, name in ipairs(names) do
  local layer = index == 1 and sprite.layers[1] or sprite:newLayer()
  layer.name = name
  local image = Image{fromFile=root .. "/hearth_surveyor_" .. name .. ".png"}
  if index == 1 then
    sprite.cels[1].image = image
  else
    sprite:newCel(layer, 1, image, Point(0, 0))
  end
end

if app.fs.isFile(out) then os.remove(out) end
sprite:saveAs(out)
sprite:close()
