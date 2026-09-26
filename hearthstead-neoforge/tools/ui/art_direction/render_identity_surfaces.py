#!/usr/bin/env python3
"""Author Hearthstead's three identity UI surfaces as original pixel art.

The output is intentionally split into editable visual layers.  Aseprite then
assembles those layers into the canonical .aseprite sources; Java integration
is a later, separately reviewed step.
"""
from pathlib import Path
import json
import random
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build" / "ui_art_direction"
LAYERS = ROOT / "tools" / "ui" / "art_direction" / "layers"
RUNTIME = ROOT / "src" / "main" / "resources" / "assets" / "hearthstead" / "textures" / "gui" / "identity"
SEED = 41027
FONT = ImageFont.load_default()

C = {
    "void": (12, 12, 12, 255), "shadow": (0, 0, 0, 150),
    "coal0": (24, 22, 20, 255), "coal1": (38, 34, 30, 255), "coal2": (55, 48, 40, 255),
    "iron0": (43, 45, 43, 255), "iron1": (74, 75, 68, 255), "iron2": (111, 109, 94, 255),
    "oak0": (48, 31, 21, 255), "oak1": (78, 49, 28, 255), "oak2": (117, 76, 39, 255), "oak3": (157, 108, 55, 255),
    "brass0": (98, 70, 28, 255), "brass1": (159, 119, 48, 255), "brass2": (211, 172, 82, 255),
    "paper0": (103, 81, 52, 255), "paper1": (181, 151, 98, 255), "paper2": (226, 203, 150, 255), "paper3": (244, 226, 178, 255),
    "ink": (42, 31, 22, 255), "text": (238, 224, 189, 255), "muted": (176, 159, 126, 255),
    "green": (77, 126, 76, 255), "red": (147, 65, 53, 255), "blue": (61, 91, 104, 255),
    "soil": (83, 58, 34, 255), "grass": (83, 108, 62, 255), "water": (64, 99, 111, 255),
}


def canvas(size):
    return Image.new("RGBA", size, (0, 0, 0, 0))


def px_text(draw, xy, text, fill, anchor=None):
    x, y = xy
    if anchor == "mm":
        box = draw.textbbox((0, 0), text, font=FONT)
        x -= (box[2] - box[0]) // 2
    draw.text((x + 1, y + 1), text, font=FONT, fill=(0, 0, 0, 150))
    draw.text((x, y), text, font=FONT, fill=fill)


def bevel(draw, box, face, hi, lo, width=2):
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=face)
    for i in range(width):
        draw.line((x0+i, y0+i, x1-i, y0+i), fill=hi)
        draw.line((x0+i, y0+i, x0+i, y1-i), fill=hi)
        draw.line((x0+i, y1-i, x1-i, y1-i), fill=lo)
        draw.line((x1-i, y0+i, x1-i, y1-i), fill=lo)


def recess_well(draw, box, face):
    """A carved recess: occluded top/right, reflected lower/left lip."""
    x0,y0,x1,y1=box
    draw.rectangle(box,fill=face)
    draw.line((x0,y0,x1,y0),fill=(8,7,6,255),width=3)
    draw.line((x1-2,y0,x1-2,y1),fill=(9,8,7,255),width=2)
    draw.line((x0+1,y1-1,x1-2,y1-1),fill=C["iron1"])
    draw.line((x0+1,y0+2,x0+1,y1-1),fill=C["oak2"])


def wood(draw, box, seed):
    bevel(draw, box, C["oak1"], C["oak3"], C["oak0"], 2)
    r = random.Random(seed)
    x0, y0, x1, y1 = box
    for y in range(y0+4, y1-2, 7):
        draw.line((x0+3, y, x1-3, y), fill=C["oak2"])
        for _ in range(2):
            x = r.randint(x0+7, max(x0+7, x1-8))
            draw.line((x, y-1, min(x+6, x1-3), y-1), fill=C["oak0"])


def iron_rivet(draw, x, y):
    draw.point((x+1, y+1), fill=C["coal0"])
    draw.point((x, y), fill=C["iron2"])
    draw.point((x+1, y), fill=C["iron1"])


def table_backdrop(draw, size, seed):
    """Quiet rear plane: broad charred boards, deliberately below the object."""
    w,h=size
    draw.rectangle((0,0,w-1,h-1),fill=(10,9,8,236))
    r=random.Random(seed)
    for y in range(10,h,22):
        draw.line((0,y,w-1,y),fill=(29,23,18,180))
        draw.line((0,y+1,w-1,y+1),fill=(7,7,7,190))
        for _ in range(3):
            x=r.randrange(0,w)
            draw.line((x,y-5,min(w-1,x+r.randrange(8,24)),y-5),fill=(38,27,19,100))


def iron_clasp(draw, box):
    x0,y0,x1,y1=box
    draw.rectangle((x0+3,y0+4,x1+3,y1+4),fill=C["shadow"])
    bevel(draw,box,C["iron1"],C["iron2"],C["iron0"],1)
    iron_rivet(draw,x0+2,y0+2); iron_rivet(draw,x1-3,y1-3)


def flame_emblem(draw, cx, cy):
    draw.polygon([(cx,cy-10),(cx+4,cy-4),(cx+2,cy+3),(cx+7,cy),(cx+6,cy+8),(cx,cy+12),(cx-6,cy+8),(cx-7,cy+1),(cx-2,cy+4),(cx-3,cy-2)], fill=C["brass1"])
    draw.polygon([(cx,cy-4),(cx+2,cy+1),(cx,cy+7),(cx-3,cy+4)], fill=C["paper3"])
    draw.rectangle((cx-10,cy+10,cx+10,cy+12), fill=C["iron0"])
    draw.line((cx-8,cy+10,cx+8,cy+10), fill=C["iron2"])


def axe_icon(draw, x, y):
    """Small readable item silhouette for dispatch tickets."""
    draw.line((x+3,y+13,x+12,y+3), fill=C["oak3"], width=2)
    draw.line((x+4,y+13,x+13,y+4), fill=C["oak0"])
    draw.polygon([(x+8,y+2),(x+14,y+1),(x+16,y+5),(x+12,y+8),(x+9,y+5)], fill=C["iron2"])
    draw.line((x+9,y+2,x+14,y+2), fill=(174,171,151,255))


def hearth_layers(size):
    w, h = size
    bg, frame, content, foreground, labels, interaction = [canvas(size) for _ in range(6)]
    bd, fd, cd, od, ld, idraw = map(ImageDraw.Draw, (bg, frame, content, foreground, labels, interaction))
    table_backdrop(bd,size,SEED+10)
    panel_w, panel_h = min(w-16, 396), min(h-12, 226)
    x, y = (w-panel_w)//2, (h-panel_h)//2
    # Object silhouette: projecting stone feet, broad oak spine, iron shoulders.
    fd.rectangle((x+8,y+5,x+panel_w-9,y+panel_h-4), fill=C["shadow"])
    fd.polygon([(x+5,y+9),(x+14,y),(x+panel_w-15,y),(x+panel_w-6,y+9),
                (x+panel_w-6,y+panel_h-11),(x+panel_w-14,y+panel_h-3),
                (x+14,y+panel_h-3),(x+5,y+panel_h-11)], fill=C["iron0"])
    wood(fd, (x+10,y+6,x+panel_w-11,y+panel_h-8), SEED)
    bevel(fd, (x+16,y+13,x+panel_w-17,y+panel_h-14), C["coal1"], C["iron1"], C["coal0"], 2)
    # Stone side cheeks create a hearth-shaped profile instead of a monitor.
    for sx in (x+3, x+panel_w-12):
        fd.rectangle((sx,y+34,sx+9,y+panel_h-34), fill=C["iron1"])
        fd.rectangle((sx+2,y+37,sx+7,y+panel_h-37), fill=C["coal2"])
    for rx, ry in ((x+9,y+8),(x+panel_w-12,y+8),(x+9,y+panel_h-11),(x+panel_w-12,y+panel_h-11)):
        iron_rivet(fd, rx, ry)
    # Foreground hardware overlaps the wood spine and throws its own shadow.
    iron_clasp(od,(x+panel_w//2-17,y+4,x+panel_w//2+17,y+12))
    flame_emblem(od, x+panel_w//2, y+20)
    iron_clasp(od,(x+2,y+74,x+12,y+117)); iron_clasp(od,(x+panel_w-13,y+74,x+panel_w-3,y+117))
    # Central communal chest is the focal point.
    cx0, cy0, cx1, cy1 = x+102, y+48, x+panel_w-103, y+139
    recess_well(cd, (cx0,cy0,cx1,cy1), (48,43,36,255))
    cd.rectangle((cx0+5,cy0+8,cx1-5,cy0+12), fill=C["oak1"])
    cd.line((cx0+5,cy0+8,cx1-5,cy0+8), fill=C["oak3"])
    # Real 18x18 inventory rhythm, fewer competing cards.
    gx, gy = cx0+11, cy0+22
    cols = max(5, min(9, (cx1-cx0-22)//18))
    for row in range(3):
        for col in range(cols):
            bx, by = gx+col*18, gy+row*18
            recess_well(cd, (bx,by,bx+16,by+16), C["coal0"])
    # A few real item silhouettes stop the communal store from reading as an
    # empty black matrix while keeping most wells visually quiet.
    cd.rectangle((gx+3,gy+6,gx+12,gy+10),fill=C["oak2"])
    cd.ellipse((gx+10,gy+5,gx+14,gy+11),fill=C["paper1"],outline=C["oak0"])
    cd.polygon([(gx+22,gy+13),(gx+25,gy+3),(gx+29,gy+10),(gx+32,gy+2),(gx+34,gy+13)],fill=C["grass"])
    cd.polygon([(gx+4*18+3,gy+5),(gx+4*18+13,gy+3),(gx+4*18+14,gy+11),(gx+4*18+5,gy+13)],fill=C["iron2"])
    cd.line((gx+4*18+5,gy+5,gx+4*18+12,gy+4),fill=(177,174,154,255))
    # Left population carving and right readiness plate.
    left_plate=[(x+21,y+57),(x+25,y+53),(x+87,y+53),(x+91,y+57),(x+91,y+131),(x+87,y+135),(x+25,y+135),(x+21,y+131)]
    right_plate=[(x+panel_w-92,y+57),(x+panel_w-88,y+53),(x+panel_w-26,y+53),(x+panel_w-22,y+57),(x+panel_w-22,y+131),(x+panel_w-26,y+135),(x+panel_w-88,y+135),(x+panel_w-92,y+131)]
    for plate in (left_plate,right_plate):
        cd.polygon([(px+2,py+3) for px,py in plate],fill=C["shadow"])
        cd.polygon(plate,fill=C["coal2"])
        cd.line(plate[:4],fill=C["oak2"],width=1)
    # Carved soot marks make these structural wings read as material, not CSS cards.
    for yy in (y+57,y+131):
        cd.line((x+25,yy,x+87,yy), fill=C["oak0"])
        cd.line((x+panel_w-88,yy,x+panel_w-26,yy), fill=C["oak0"])
    for xx in (x+25,x+87,x+panel_w-88,x+panel_w-26): iron_rivet(cd,xx,y+57)
    # Bottom open leather ledger flap.
    cd.polygon([(x+36,y+154),(x+panel_w-31,y+154),(x+panel_w-24,y+panel_h-20),(x+29,y+panel_h-20)], fill=C["shadow"])
    cd.polygon([(x+33,y+151),(x+panel_w-34,y+151),(x+panel_w-27,y+panel_h-23),(x+26,y+panel_h-23)], fill=C["paper0"])
    cd.polygon([(x+37,y+155),(x+panel_w//2-3,y+155),(x+panel_w//2-7,y+panel_h-27),(x+31,y+panel_h-27)], fill=C["paper2"])
    cd.polygon([(x+panel_w//2+3,y+155),(x+panel_w-38,y+155),(x+panel_w-32,y+panel_h-27),(x+panel_w//2+7,y+panel_h-27)], fill=C["paper2"])
    cd.line((x+31,y+panel_h-27,x+panel_w//2-7,y+panel_h-27),fill=C["paper0"])
    cd.line((x+panel_w//2+7,y+panel_h-27,x+panel_w-32,y+panel_h-27),fill=C["paper0"])
    cd.line((x+panel_w//2,y+156,x+panel_w//2,y+panel_h-28), fill=C["paper0"])
    px_text(ld, (x+27,y+19), "ALDERWATCH", C["text"])
    px_text(ld, (x+panel_w//2,y+35), "COMMUNAL STORES", C["brass2"], "mm")
    px_text(ld, (x+27,y+61), "SETTLERS", C["muted"]); px_text(ld, (x+27,y+76), "7 / 10", C["paper3"])
    px_text(ld, (x+27,y+99), "WORKING", C["muted"]); px_text(ld, (x+27,y+114), "5", C["paper3"])
    px_text(ld, (x+panel_w-86,y+61), "HEARTH", C["muted"]); px_text(ld, (x+panel_w-86,y+76), "STEADY", C["green"])
    px_text(ld, (x+panel_w-86,y+99), "SUPPLY", C["muted"]); px_text(ld, (x+panel_w-86,y+114), "6 DAYS", C["paper3"])
    compact=panel_w < 360
    px_text(ld, (x+43,y+161), "NEW ARRIVAL" if compact else "TRAVELLER'S ENTRY", C["ink"])
    px_text(ld, (x+43,y+177), "Family seeks shelter" if compact else "A new household seeks shelter.", C["ink"])
    px_text(ld, (x+panel_w//2+13,y+161), "NEXT" if compact else "NEXT DECISION", C["ink"])
    px_text(ld, (x+panel_w//2+13,y+177), "Review family" if compact else "Review the waiting family", C["ink"])
    # Focus state: exactly one-pixel raised edge and a changed cast shadow.
    idraw.line((x+panel_w//2+8,y+153,x+panel_w-39,y+153),fill=C["paper3"])
    idraw.line((x+panel_w-32,y+158,x+panel_w-28,y+panel_h-26),fill=(34,23,16,190),width=2)
    return [bg, frame, content, foreground, labels, interaction]


def development_layers(size):
    w, h = size
    bg, frame, map_layer, marks, foreground, labels = [canvas(size) for _ in range(6)]
    bd, fd, md, kd, od, ld = map(ImageDraw.Draw, (bg, frame, map_layer, marks, foreground, labels))
    table_backdrop(bd,size,SEED+11)
    pw, ph = min(w-12, 464), min(h-10, 248); x,y=(w-pw)//2,(h-ph)//2
    fd.rectangle((x+5,y+7,x+pw-2,y+ph-1), fill=C["shadow"])
    wood(fd, (x,y,x+pw-1,y+ph-1), SEED+1)
    # Map cloth: clipped/chamfered, not a card.
    mx0,my0,mx1,my1=x+13,y+18,x+pw-117,y+ph-14
    md.polygon([(mx0+8,my0+4),(mx1-4,my0+6),(mx1+3,my0+12),(mx1+1,my1-1),(mx1-6,my1+4),(mx0+6,my1+2),(mx0+3,my1-4)], fill=C["shadow"])
    md.polygon([(mx0+5,my0),(mx1-7,my0+2),(mx1,my0+8),(mx1-2,my1-5),(mx1-9,my1),(mx0+3,my1-2),(mx0,my1-8)], fill=C["paper1"])
    md.polygon([(mx0+8,my0+5),(mx1-9,my0+6),(mx1-6,my1-8),(mx0+6,my1-6)], fill=C["paper2"])
    r=random.Random(SEED+2)
    for _ in range(120):
        px=r.randint(mx0+8,mx1-10); py=r.randint(my0+8,my1-10)
        md.point((px,py), fill=(190+r.randint(-14,12),161+r.randint(-10,10),105,90))
    # River and terrain shapes.
    kd.line([(mx0+18,my0+20),(mx0+52,my0+43),(mx0+75,my0+74),(mx0+111,my0+89),(mx1-20,my1-18)], fill=C["water"], width=5)
    kd.line([(mx0+18,my0+20),(mx0+52,my0+43),(mx0+75,my0+74),(mx0+111,my0+89),(mx1-20,my1-18)], fill=(111,145,148,255), width=2)
    for tx,ty in ((mx0+45,my0+25),(mx0+58,my0+20),(mx0+134,my0+34),(mx0+148,my0+28),(mx0+165,my0+39),(mx0+91,my0+118)):
        kd.polygon([(tx,ty-6),(tx-5,ty+3),(tx+5,ty+3)], fill=C["grass"]); kd.rectangle((tx-1,ty+3,tx+1,ty+7), fill=C["oak0"])
    # Organic progression road, no technical connector grid.
    route=[(mx0+28,my1-26),(mx0+75,my1-54),(mx0+112,my1-83),(mx0+157,my1-62),(mx1-34,my0+42)]
    kd.line(route, fill=C["paper0"], width=5); kd.line(route, fill=C["brass0"], width=1)
    landmarks=[(route[0],"H",True),(route[1],"L",True),(route[2],"F",False),(route[3],"G",False),(route[4],"T",False)]
    for (nx,ny),letter,open_ in landmarks:
        fillc=C["brass2"] if open_ else C["iron1"]
        kd.ellipse((nx-10,ny-10,nx+10,ny+10), fill=C["coal0"], outline=fillc, width=2)
        kd.rectangle((nx-5,ny-4,nx+5,ny+5), fill=C["oak1"] if open_ else C["coal2"])
        px_text(ld,(nx,ny-4),letter,C["paper3"] if open_ else C["muted"],"mm")
    # Folded research document on the right, one focused choice.
    dx0,dy0,dx1,dy1=x+pw-109,y+12,x+pw-12,y+ph-18
    md.polygon([(dx0+4,dy0+9),(dx0+11,dy0+4),(dx1+4,dy0+6),(dx1+1,dy1+4),(dx0+8,dy1+1)], fill=C["shadow"])
    md.polygon([(dx0,dy0+5),(dx0+7,dy0),(dx1,dy0+2),(dx1-3,dy1),(dx0+4,dy1-3)], fill=C["paper0"])
    md.polygon([(dx0+5,dy0+7),(dx1-5,dy0+6),(dx1-8,dy1-6),(dx0+8,dy1-8)], fill=C["paper3"])
    # Wax seal focal point.
    od.ellipse((dx1-22,dy0+15,dx1-6,dy0+31), fill=C["shadow"])
    od.ellipse((dx1-25,dy0+12,dx1-9,dy0+28), fill=C["red"], outline=(92,38,33,255))
    od.line((dx1-21,dy0+20,dx1-13,dy0+20), fill=(204,111,80,255))
    # Rolled map dowel and corner compass sit above parchment.
    od.rectangle((mx0+3,my1-7,mx1-5,my1-3),fill=C["oak0"])
    od.line((mx0+4,my1-8,mx1-6,my1-8),fill=C["oak3"])
    od.ellipse((mx0+8,my1-24,mx0+28,my1-4),fill=C["coal0"],outline=C["brass2"],width=2)
    od.line((mx0+18,my1-21,mx0+18,my1-7),fill=C["paper3"])
    od.line((mx0+12,my1-14,mx0+24,my1-14),fill=C["paper3"])
    iron_clasp(od,(x+pw-21,y+ph-17,x+pw-8,y+ph-7))
    px_text(ld,(mx0+7,y+7),"SETTLEMENT SURVEY",C["text"])
    px_text(ld,(dx0+12,dy0+14),"FARMSTEAD",C["ink"])
    px_text(ld,(dx0+12,dy0+32),"Unlocks",C["paper0"])
    px_text(ld,(dx0+12,dy0+43),"Farmhouse plan",C["ink"])
    px_text(ld,(dx0+12,dy0+61),"Requires",C["paper0"])
    px_text(ld,(dx0+12,dy0+72),"Lumber Camp",C["ink"])
    px_text(ld,(dx0+12,dy0+89),"Cost",C["paper0"])
    px_text(ld,(dx0+12,dy0+100),"12 Oak Logs",C["ink"])
    bevel(kd,(dx0+12,dy1-30,dx1-13,dy1-13),C["oak1"],C["oak3"],C["oak0"],1)
    px_text(ld,((dx0+dx1)//2,dy1-25),"RESEARCH",C["paper3"],"mm")
    px_text(ld,(x+25,y+ph-19),"Wheel: zoom   Drag: survey",C["text"])
    # Landmarks and button bodies are below their dynamic labels. The final
    # state layer only lifts the selected landmark by one logical pixel.
    map_layer.alpha_composite(marks)
    interaction=canvas(size); state_draw=ImageDraw.Draw(interaction)
    selected_x,selected_y=route[1]
    state_draw.arc((selected_x-11,selected_y-12,selected_x+11,selected_y+10),190,345,fill=C["paper3"],width=1)
    state_draw.arc((selected_x-10,selected_y-9,selected_x+12,selected_y+13),10,165,fill=(24,17,11,190),width=2)
    return [bg, frame, map_layer, foreground, labels, interaction]


def ticket(draw, box, selected, pin, item, route, state, tone):
    x0,y0,x1,y1=box
    paper=C["paper3"] if selected else C["paper2"]
    draw.polygon([(x0+2,y0),(x1-5,y0+1),(x1,y0+5),(x1-2,y1),(x0+4,y1-2),(x0,y0+5)], fill=C["shadow"])
    paper_shape=[(x0+1,y0),(x1-6,y0+1),(x1-1,y0+5),(x1-3,y1-2),(x0+4,y1-3),(x0,y0+4)]
    draw.polygon(paper_shape, fill=paper)
    draw.line((x0+4,y1-3,x1-3,y1-2),fill=C["paper0"])
    draw.line((x1-2,y0+6,x1-3,y1-2),fill=C["paper0"])
    draw.ellipse((pin-3,y0+3,pin+3,y0+9), fill=C["red"], outline=(91,40,32,255))
    draw.rectangle((x0+7,y0+16,x0+22,y0+31), fill=C["coal1"], outline=C["paper0"])
    axe_icon(draw,x0+7,y0+15)
    px_text(draw,(x0+29,y0+15),item,C["ink"])
    px_text(draw,(x0+29,y0+29),route,C["paper0"])
    px_text(draw,(x0+29,y0+43),state,tone)


def courier_layers(size):
    w,h=size
    bg, frame, tickets, foreground, labels, interaction=[canvas(size) for _ in range(6)]
    bd,fd,td,od,ld,idraw=map(ImageDraw.Draw,(bg,frame,tickets,foreground,labels,interaction))
    table_backdrop(bd,size,SEED+12)
    pw,ph=min(w-12,410),min(h-12,226); x,y=(w-pw)//2,(h-ph)//2
    fd.rectangle((x+5,y+7,x+pw,y+ph), fill=C["shadow"])
    wood(fd,(x,y,x+pw-1,y+ph-1),SEED+4)
    # Felt dispatch field and rope-bound title plate.
    bevel(fd,(x+10,y+12,x+pw-11,y+ph-13),C["coal2"],C["iron1"],C["coal0"],2)
    fd.line((x+22,y+35,x+pw-22,y+35),fill=C["oak3"],width=2)
    for px in range(x+24,x+pw-24,9):
        fd.arc((px,y+31,px+8,y+39),0,180,fill=C["brass0"])
    # Three pinned tickets: selected is larger, queue items recede.
    compact=pw < 360
    ticket(td,(x+21,y+52,x+pw-128,y+112),True,x+49,"IRON AXE  x1","Warehouse > Camp" if compact else "Warehouse > Lumber Camp","IN TRANSIT  42s",C["green"])
    ticket(td,(x+28,y+124,x+pw-143,y+174),False,x+54,"WOODEN HOE  x1","Warehouse > Farm" if compact else "Warehouse > Farmhouse","BLOCKED",C["red"])
    # Right hand dispatch docket gives selected-item detail only.
    dx0,dy0,dx1,dy1=x+pw-118,y+47,x+pw-18,y+ph-23
    td.polygon([(dx0+7,dy0+4),(dx1+4,dy0+8),(dx1+2,dy1+4),(dx0+4,dy1+1)], fill=C["shadow"])
    td.polygon([(dx0+3,dy0),(dx1,dy0+4),(dx1-2,dy1),(dx0,dy1-3)], fill=C["paper0"])
    td.polygon([(dx0+7,dy0+5),(dx1-5,dy0+7),(dx1-7,dy1-6),(dx0+5,dy1-8)], fill=C["paper3"])
    # Pins and clips are a true foreground plane, above both paper and board.
    for tx,ty in ((x+49,y+58),(x+54,y+130),(dx1-13,dy0+10)):
        od.ellipse((tx-3,ty-3,tx+3,ty+3),fill=C["shadow"])
        od.ellipse((tx-4,ty-4,tx+2,ty+2),fill=C["red"],outline=(91,40,32,255))
        od.point((tx-2,ty-3),fill=(214,126,91,255))
    iron_clasp(od,(x+pw//2-12,y+ph-14,x+pw//2+12,y+ph-5))
    td.rectangle((dx0+14,dy0+18,dx0+34,dy0+38), fill=C["coal1"], outline=C["paper0"])
    td.line((dx0+18,dy0+33,dx0+29,dy0+22), fill=C["iron2"], width=2)
    px_text(ld,(x+21,y+16),"COURIER DISPATCH",C["text"])
    status="2 ACTIVE"
    status_w=ld.textbbox((0,0),status,font=FONT)[2]
    px_text(ld,(x+pw-22-status_w,y+17),status,C["brass2"],None)
    px_text(ld,(dx0+12,dy0+48),"FREYA",C["ink"])
    px_text(ld,(dx0+12,dy0+61),"Carrying now",C["paper0"])
    px_text(ld,(dx0+12,dy0+78),"NEXT STOP",C["ink"])
    px_text(ld,(dx0+12,dy0+91),"Lumber Camp",C["paper0"])
    px_text(ld,(dx0+12,dy0+112),"Priority  #1",C["red"])
    bevel(td,(dx0+12,dy1-28,dx1-12,dy1-12),C["oak1"],C["oak3"],C["oak0"],1)
    px_text(ld,((dx0+dx1)//2,dy1-24),"OPEN REQUEST",C["paper3"],"mm")
    px_text(ld,(x+24,y+ph-25),"Drag a ticket to reorder the route",C["text"])
    idraw.line((x+20,y+51,x+pw-128,y+51),fill=C["paper3"])
    idraw.line((x+pw-126,y+56,x+pw-124,y+108),fill=(18,14,11,190),width=2)
    return [bg,frame,tickets,foreground,labels,interaction]


def building_hero(draw,cx,cy):
    """Original tiny isometric lumber camp cutout, readable at GUI scale."""
    # Raised foundation and cast shadow.
    draw.polygon([(cx-42,cy+24),(cx+4,cy+38),(cx+45,cy+21),(cx-2,cy+8)],fill=C["shadow"])
    draw.polygon([(cx-42,cy+18),(cx+3,cy+31),(cx+44,cy+15),(cx-2,cy+2)],fill=C["soil"])
    draw.line((cx-42,cy+18,cx+3,cy+31,cx+44,cy+15),fill=C["oak3"],width=2)
    # Timber hut, roof and open work bay.
    draw.polygon([(cx-21,cy-12),(cx+5,cy-23),(cx+28,cy-14),(cx+2,cy-3)],fill=C["oak2"])
    draw.polygon([(cx-21,cy-12),(cx+2,cy-3),(cx+2,cy+17),(cx-21,cy+9)],fill=C["oak1"])
    draw.polygon([(cx+2,cy-3),(cx+28,cy-14),(cx+28,cy+7),(cx+2,cy+17)],fill=C["oak0"])
    draw.polygon([(cx-27,cy-14),(cx+4,cy-28),(cx+34,cy-16),(cx+3,cy-3)],fill=C["coal1"])
    draw.line((cx-26,cy-15,cx+4,cy-29,cx+34,cy-17),fill=C["iron2"])
    draw.rectangle((cx-13,cy-1,cx-4,cy+12),fill=C["coal0"])
    # Recognisable stacked logs beside the camp.
    for i in range(3):
        lx=cx+18+i*5; ly=cy+9-i
        draw.line((lx-6,ly,lx+8,ly+4),fill=C["oak3"],width=3)
        draw.ellipse((lx+6,ly+1,lx+10,ly+5),fill=C["paper1"],outline=C["oak0"])


def material_token(draw,x,y,kind):
    draw.ellipse((x+2,y+3,x+20,y+21),fill=C["shadow"])
    draw.ellipse((x,y,x+18,y+18),fill=C["coal1"],outline=C["brass1"],width=2)
    if kind=="log":
        draw.rectangle((x+4,y+6,x+14,y+11),fill=C["oak2"])
        draw.ellipse((x+11,y+5,x+15,y+12),fill=C["paper1"],outline=C["oak0"])
    else:
        draw.polygon([(x+4,y+12),(x+6,y+5),(x+13,y+3),(x+15,y+12)],fill=C["iron1"])


def plaque_workbench_layers(size):
    w,h=size
    bg,frame,inset,foreground,labels,interaction=[canvas(size) for _ in range(6)]
    bd,fd,pd,od,ld,idraw=map(ImageDraw.Draw,(bg,frame,inset,foreground,labels,interaction))
    table_backdrop(bd,size,SEED+14)
    pw,ph=min(w-12,438),min(h-12,230); x,y=(w-pw)//2,(h-ph)//2
    # Angled drafting board: asymmetric silhouette, not a window.
    board=[(x+8,y+8),(x+pw-18,y+2),(x+pw-5,y+17),(x+pw-11,y+ph-9),(x+19,y+ph-2),(x+2,y+ph-18)]
    fd.polygon([(px+4,py+5) for px,py in board],fill=C["shadow"])
    fd.polygon(board,fill=C["oak1"])
    fd.line(board[:3],fill=C["oak3"],width=2); fd.line(board[3:],fill=C["oak0"],width=3)
    for yy in range(y+26,y+ph-16,13): fd.line((x+12,yy,x+pw-17,yy-4),fill=(101,63,32,255))
    # Quiet recessed cutting mat for the raised miniature.
    recess_well(pd,(x+17,y+31,x+pw-144,y+139),C["coal1"])
    hero=canvas((96,72)); building_hero(ImageDraw.Draw(hero),48,38)
    hero=hero.resize((120,90),Image.Resampling.NEAREST)
    hero_cx=(x+17+x+pw-144)//2
    inset.alpha_composite(hero,(hero_cx-60,y+43))
    # Build plan parchment partly tucked beneath its ruler.
    rx0,ry0,rx1,ry1=x+pw-136,y+22,x+pw-18,y+ph-24
    pd.polygon([(rx0+5,ry0+7),(rx1+5,ry0+3),(rx1+3,ry1+5),(rx0+8,ry1+1)],fill=C["shadow"])
    pd.polygon([(rx0,ry0+4),(rx1,ry0),(rx1-2,ry1),(rx0+4,ry1-4)],fill=C["paper0"])
    pd.polygon([(rx0+4,ry0+7),(rx1-4,ry0+5),(rx1-6,ry1-6),(rx0+8,ry1-8)],fill=C["paper3"])
    pd.line((rx0+8,ry1-8,rx1-6,ry1-6),fill=C["paper0"])
    # Material-token shelf is part of the workbench, not another card.
    pd.polygon([(x+22,y+151),(x+pw-135,y+148),(x+pw-128,y+ph-20),(x+28,y+ph-16)],fill=C["coal0"])
    pd.line((x+23,y+151,x+pw-135,y+148),fill=C["iron2"],width=2)
    material_token(pd,x+35,y+161,"log"); material_token(pd,x+92,y+159,"stone")
    # Foreground brass ruler occludes the plan edge.
    od.polygon([(rx0-5,ry0+4),(rx0+3,ry0+2),(rx0+8,ry1-7),(rx0,ry1-4)],fill=C["shadow"])
    od.polygon([(rx0-8,ry0),(rx0,ry0-2),(rx0+5,ry1-11),(rx0-3,ry1-8)],fill=C["brass1"])
    for yy in range(ry0+8,ry1-16,10): od.line((rx0-5,yy,rx0-1,yy),fill=C["brass2"])
    # Required-role emblem in a carved socket.
    od.ellipse((x+21,y+15,x+49,y+43),fill=C["shadow"])
    od.ellipse((x+18,y+12,x+46,y+40),fill=C["coal0"],outline=C["iron2"],width=2)
    od.ellipse((x+24,y+18,x+40,y+34),fill=C["brass0"],outline=C["brass2"])
    od.line((x+28,y+27,x+36,y+21),fill=C["paper3"],width=2)
    # Mechanical approval lever projects in front of the plan.
    od.rectangle((rx1-31,ry1-29,rx1-9,ry1-13),fill=C["coal0"],outline=C["iron2"])
    od.line((rx1-24,ry1-22,rx1-16,ry1-34),fill=C["brass2"],width=3)
    od.ellipse((rx1-20,ry1-39,rx1-12,ry1-31),fill=C["red"],outline=C["paper1"])
    px_text(ld,(x+54,y+16),"LUMBER CAMP",C["text"])
    px_text(ld,(x+54,y+27),"WORKPLACE PLAQUE",C["muted"])
    px_text(ld,(x+29,y+144),"MATERIALS",C["brass2"])
    px_text(ld,(x+57,y+166),"32",C["text"]); px_text(ld,(x+114,y+164),"8",C["text"])
    px_text(ld,(rx0+12,ry0+12),"BUILD PLAN",C["ink"])
    px_text(ld,(rx0+12,ry0+29),"Staff",C["paper0"]); px_text(ld,(rx0+12,ry0+40),"Lumberer",C["ink"])
    px_text(ld,(rx0+12,ry0+55),"Produces",C["paper0"]); px_text(ld,(rx0+12,ry0+66),"Oak Logs",C["ink"])
    px_text(ld,(rx0+12,ry0+82),"Plan recipe",C["paper0"])
    # Exact compact 3x3 recipe, with legend directly below.
    gx,gy=rx0+13,ry0+94
    recipe=(("P","L","P"),("L","E","L"),("P","L","P"))
    for row in range(3):
        for col in range(3):
            bx,by=gx+col*12,gy+row*12
            pd.rectangle((bx,by,bx+9,by+9),fill=C["paper2"],outline=C["paper0"])
            px_text(ld,(bx+5,by+1),recipe[row][col],C["ink"],"mm")
    px_text(ld,(gx+40,gy+1),"P Plank",C["ink"]); px_text(ld,(gx+40,gy+12),"L Log",C["ink"]); px_text(ld,(gx+40,gy+23),"E Emblem",C["ink"])
    px_text(ld,(rx0+11,ry1-45),"READY TO BUILD",C["green"])
    # Focused lever state uses a one-pixel top/left lift and darker shadow.
    idraw.line((rx1-31,ry1-30,rx1-9,ry1-30),fill=C["brass2"])
    idraw.line((rx1-8,ry1-28,rx1-8,ry1-12),fill=(18,13,9,210),width=2)
    return [bg,frame,inset,foreground,labels,interaction]


SURFACES={"hearth_ledger":hearth_layers,"development_survey":development_layers,
          "courier_dispatch":courier_layers,"plaque_workbench":plaque_workbench_layers}
VIEWPORTS=[(320,240),(427,240),(512,274)]


def save_layers(name, size, layers):
    stem=f"{name}_{size[0]}x{size[1]}"
    folder=LAYERS/stem; folder.mkdir(parents=True,exist_ok=True)
    names=["back_cast_shadow","frame_wood","inset_paper_or_leather",
           "foreground_hardware","text_and_icons","interaction_state"]
    merged=canvas(size)
    for label,layer in zip(names,layers):
        layer.save(folder/f"{label}.png")
        merged.alpha_composite(layer)
    OUT.mkdir(parents=True,exist_ok=True)
    merged.save(OUT/f"{stem}.png")


def runtime_assets():
    RUNTIME.mkdir(parents=True,exist_ok=True)
    # Screen-specific reusable fragments; no text is baked into runtime art.
    bits={
      "hearth_emblem":(32,32),"courier_ticket_idle":(96,54),"courier_ticket_selected":(128,62),
      "courier_ticket_surface_idle":(12,12),"courier_ticket_surface_selected":(12,12),
      "courier_pin":(8,8),
      "development_landmark_open":(24,24),"development_landmark_locked":(24,24),
    }
    for name,size in bits.items():
        im=canvas(size); d=ImageDraw.Draw(im)
        if name=="hearth_emblem": flame_emblem(d,16,14)
        elif name.startswith("courier_ticket_surface"):
            selected=name.endswith("selected")
            face=C["paper3"] if selected else C["paper2"]
            im.alpha_composite(frame(12,4,[(C["paper0"],C["paper0"]),
                (face,C["paper0"]),(face,face),(face,face)],face))
        elif name=="courier_pin":
            d.ellipse((1,2,7,8),fill=C["shadow"])
            d.ellipse((0,0,6,6),fill=C["red"],outline=(91,40,32,255))
            d.point((1,1),fill=(214,126,91,255))
        elif name.startswith("courier"):
            x0,y0,x1,y1=1,1,size[0]-2,size[1]-2
            paper=C["paper3"] if name.endswith("selected") else C["paper2"]
            shape=[(x0+1,y0),(x1-6,y0+1),(x1-1,y0+5),(x1-3,y1-2),(x0+4,y1-3),(x0,y0+4)]
            d.polygon([(px+2,py+3) for px,py in shape],fill=C["shadow"])
            d.polygon(shape,fill=paper)
            d.line((x0+4,y1-3,x1-3,y1-2),fill=C["paper0"])
            d.line((x1-2,y0+6,x1-3,y1-2),fill=C["paper0"])
            pin=size[0]//2
            d.ellipse((pin-3,y0+3,pin+3,y0+9),fill=C["red"],outline=(91,40,32,255))
        elif name.startswith("development"):
            open_=name.endswith("open"); col=C["brass2"] if open_ else C["iron1"]
            d.ellipse((2,2,size[0]-3,size[1]-3),fill=C["coal0"],outline=col,width=2)
            d.rectangle((8,8,size[0]-9,size[1]-8),fill=C["oak1"] if open_ else C["coal2"])
        im.save(RUNTIME/f"{name}.png")


def main():
    for name,fn in SURFACES.items():
        for size in VIEWPORTS: save_layers(name,size,fn(size))
    runtime_assets()
    # Review sheet at the canonical logical viewport. Gaps are deliberate: it
    # must be obvious when one surface visually leaks into another.
    sheet=Image.new("RGBA",(427,240*len(SURFACES)+8*(len(SURFACES)-1)),(10,10,10,255))
    for index,name in enumerate(SURFACES):
        sheet.alpha_composite(Image.open(OUT/f"{name}_427x240.png"),(0,index*248))
    sheet.save(OUT/"identity_surfaces_review_sheet.png")
    value_sheet=sheet.convert("L").convert("RGBA")
    value_sheet.save(OUT/"identity_surfaces_value_check.png")
    # 200% nearest-neighbour detail review of the depth-critical regions.
    detail=Image.new("RGBA",(427,132*len(SURFACES)),(10,10,10,255))
    crops={
      "hearth_ledger":(92,38,335,146),
      "development_survey":(10,12,224,120),
      "courier_dispatch":(18,42,232,150),
      "plaque_workbench":(14,15,228,123),
    }
    for index,(name,box) in enumerate(crops.items()):
        source=Image.open(OUT/f"{name}_427x240.png").crop(box)
        zoom=source.resize((source.width*2,source.height*2),Image.Resampling.NEAREST)
        # Constrain only by cropping, never by resampling away logical pixels.
        zoom=zoom.crop((0,0,427,128))
        detail.alpha_composite(zoom,(0,index*132))
    detail.save(OUT/"identity_surfaces_depth_detail_200pct.png")
    layer_names={name:["BACK/CAST_SHADOW","FRAME_WOOD","INSET_PAPER_OR_LEATHER",
                       "FOREGROUND_HARDWARE","TEXT_AND_ICONS","INTERACTION_STATE"]
                 for name in SURFACES}
    manifest={"version":1,"authorship":"Original Hearthstead art; no third-party assets",
              "canonical_viewports":[list(v) for v in VIEWPORTS],
              "surfaces":{name:{"layers":layer_names[name],
                                  "editable_source":f"tools/ui/art_direction/sources/{name}_427x240.aseprite"}
                          for name in SURFACES},
              "runtime_assets":[p.name for p in sorted(RUNTIME.glob("*.png"))],
              "rejected_surfaces":["plaque_workbench"],
              "integration_status":"Hearth, Development and Courier art candidates only; plaque rejected and excluded from runtime"}
    (OUT/"identity_surface_manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__": main()
