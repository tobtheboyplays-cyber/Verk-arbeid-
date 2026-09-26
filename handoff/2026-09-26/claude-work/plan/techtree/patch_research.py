import json, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
p = 'template.html'
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert a in s, a[:80]
    s = s.replace(a, b, 1)


rep("state.sel = (saved&&byId[saved])?saved:(byId['coster_cart']?'coster_cart':(nodes.find(n=>/cart/i.test(n.id))||nodes[0]).id);",
"""state.sel = (saved&&byId[saved])?saved:(byId['coster_cart']?'coster_cart':(nodes.find(n=>/cart/i.test(n.id))||nodes[0]).id);
const ROOTS=nodes.filter(n=>!(n.requires||[]).length&&n.branch==='crown'&&n.tier===1).map(n=>n.id);
state.learned=new Set(ROOTS);
try{const l=JSON.parse(localStorage.getItem('bh_tt_learned')||'null');if(Array.isArray(l))l.forEach(i=>byId[i]&&state.learned.add(i))}catch(e){}
function saveL(){try{localStorage.setItem('bh_tt_learned',JSON.stringify([...state.learned]))}catch(e){}}
function rstate(n){if(state.learned.has(n.id))return'learned';if((n.excludes||[]).some(x=>state.learned.has(x)))return'blocked';return (n.requires||[]).every(r=>state.learned.has(r))?'available':'locked'}
function learn(id){const n=byId[id];if(rstate(n)!=='available')return;state.learned.add(id);saveL();draw()}
function forget(id){if(ROOTS.includes(id))return;const drop=new Set([id]);let grew=true;while(grew){grew=false;nodes.forEach(m=>{if(state.learned.has(m.id)&&!drop.has(m.id)&&(m.requires||[]).some(r=>drop.has(r))){drop.add(m.id);grew=true}})}drop.forEach(i=>state.learned.delete(i));saveL();draw()}
function spent(){let c=0;state.learned.forEach(i=>c+=(byId[i].cost&&byId[i].cost.coins)||0);return c}""")

rep(""" nodes.forEach(n=>{(n.requires||[]).forEach(rid=>{const r=byId[rid];if(!r)return;const hot=sel&&(n.id===sel.id||rid===sel.id);const e=el('path',{d:`M${r.x} ${r.y} L${n.x} ${n.y}`,class:'edge'+(hot?' hot':'')});if(!(visible(n)&&visible(r)))e.setAttribute('opacity',.15)})});""",
""" [['arr','var(--line)'],['arrL','var(--ink)'],['arrA','var(--brass)']].forEach(([id,col])=>{const m=el('marker',{id,viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:7,markerHeight:7,orient:'auto-start-reverse'},defs);el('path',{d:'M0 0 L10 5 L0 10z',fill:col},m)});
 nodes.forEach(n=>{(n.requires||[]).forEach(rid=>{const r=byId[rid];if(!r)return;const dx=n.x-r.x,dy=n.y-r.y,L=Math.hypot(dx,dy)||1;const r1=nodeR(r)+6,r2=nodeR(n)+9;const x1=r.x+dx/L*r1,y1=r.y+dy/L*r1,x2=n.x-dx/L*r2,y2=n.y-dy/L*r2;
  const hot=sel&&(n.id===sel.id||rid===sel.id);const st=rstate(n);const lrn=state.learned.has(rid);
  const cls=hot?'edge hot':(lrn&&st==='learned'?'edge done':(lrn&&st==='available'?'edge avail':'edge'));
  const mk=(hot||cls==='edge done')?'arrL':(cls==='edge avail'?'arrA':'arr');
  const e=el('path',{d:`M${x1} ${y1} L${x2} ${y2}`,class:cls,'marker-end':`url(#${mk})`});if(!(visible(n)&&visible(r)))e.setAttribute('opacity',.12)})});""")

rep("function draw(){", "function nodeR(n){return n.type==='capstone'?17:(n.branch==='crown'?16:13)}\nfunction draw(){")
rep("""  const r=n.type==='capstone'?17:(n.branch==='crown'?16:13);""", """  const r=nodeR(n); const st=rstate(n); g.classList.add('st-'+st);""")
rep("""  shape(g,n,r,`var(--${n.branch})`);""", """  if(st==='available')el('circle',{r:r+7,class:'availring'},g);
  const lockedLook=(st==='locked'||st==='blocked');
  shape(g,n,r,lockedLook?'var(--paper2)':`var(--${n.branch})`).setAttribute('stroke',lockedLook?`var(--${n.branch})`:'var(--paper)');
  if(st==='learned')el('path',{d:'M-5 0 L-1.5 4 L5.5 -4',fill:'none',stroke:'#fff','stroke-width':2.6,'stroke-linecap':'round','stroke-linejoin':'round'},g);
  if(st==='blocked')el('path',{d:'M-5 -5 L5 5 M5 -5 L-5 5',stroke:'var(--watch)','stroke-width':2.4,'stroke-linecap':'round'},g);""")

rep("""  <p class="offers">${n.offers}</p>""", """  ${researchBox(n)}
  <p class="offers">${n.offers}</p>""")
rep(""" P.querySelectorAll('[data-go]').forEach(""", """ const rb=P.querySelector('#rbtn'); if(rb) rb.onclick=()=>{rstate(n)==='learned'?forget(n.id):learn(n.id)};
 P.querySelectorAll('[data-go]').forEach(""")
rep("function goods(g){", """function researchBox(n){const st=rstate(n);
 const miss=(n.requires||[]).filter(r=>!state.learned.has(r)).map(r=>byId[r]?byId[r].name:r);
 const blk=(n.excludes||[]).filter(x=>state.learned.has(x)).map(x=>byId[x].name);
 const label={learned:'Researched',available:'Can be researched now',locked:'Locked',blocked:'Blocked by your choice'}[st];
 const note=st==='locked'?('Research first: '+miss.join(', ')):(st==='blocked'?('You chose '+blk.join(', ')+' instead'):(st==='available'?'All requirements met. Research it to see what opens next on the map.':'Forget undoes this and everything that needed it.'));
 const btn=st==='available'?'<button id="rbtn" class="rbtn">Research</button>':((st==='learned'&&!ROOTS.includes(n.id))?'<button id="rbtn" class="rbtn ghost">Forget</button>':'');
 return `<div class="rbox st-${st}"><div><b>${label}</b><div class="rnote">${note}</div></div>${btn}</div>`}
function goods(g){""")

rep("""    <label for="q" class="eyebrow" style="text-transform:none;letter-spacing:0">Search</label>""", """    <span class="rsum" id="rsum"></span><button class="chip" id="rreset" aria-pressed="true">Reset research</button>
    <span class="sep" aria-hidden="true"></span>
    <label for="q" class="eyebrow" style="text-transform:none;letter-spacing:0">Search</label>""")
rep(" setVB(); panel();\n}", " setVB(); panel();\n document.getElementById('rsum').textContent=`Researched ${state.learned.size} of ${nodes.length} · ${spent()} Coins spent · ${nodes.filter(m=>rstate(m)==='available').length} can be researched now`;\n}")
rep("draw();\n})();", "document.getElementById('rreset').onclick=()=>{state.learned=new Set(ROOTS);saveL();draw()};\ndraw();\n})();")

rep("footer{font-size:12.5px;color:var(--ink2)}", """footer{font-size:12.5px;color:var(--ink2)}
.edge.done{stroke:var(--ink2);stroke-width:2.2}
.edge.avail{stroke:var(--brass);stroke-width:2.2}
.availring{fill:none;stroke:var(--brass);stroke-width:2.5;stroke-dasharray:4 3;animation:spin 6s linear infinite}
@keyframes spin{to{stroke-dashoffset:-42}}
.node.st-locked,.node.st-blocked{opacity:.62}
.node.st-locked.dim,.node.st-blocked.dim{opacity:.15}
.rsum{font-size:13px;color:var(--ink2);font-variant-numeric:tabular-nums}
.rbox{display:flex;justify-content:space-between;align-items:center;gap:10px;border:1px solid var(--line);border-radius:8px;padding:9px 11px;margin:6px 0 4px;background:var(--paper2)}
.rbox.st-available{border-color:var(--brass)}
.rbox.st-learned{border-color:var(--new)}
.rbox.st-blocked{border-color:var(--watch)}
.rnote{font-size:13px;color:var(--ink2)}
.rbtn{border:1px solid var(--walnut);background:var(--walnut);color:var(--paper);border-radius:6px;padding:6px 14px;font:600 14px "Alegreya Sans";cursor:pointer;white-space:nowrap}
.rbtn.ghost{background:transparent;color:var(--ink)}
@media (prefers-reduced-motion: reduce){.availring{animation:none}}""")

rep("""    <span><svg width="30" height="10"><line x1="1" y1="5" x2="29" y2="5" stroke="var(--watch)" stroke-width="1.6" stroke-dasharray="5 5"/></svg> Excludes (choose one)</span>""",
"""    <span><svg width="30" height="10"><line x1="1" y1="5" x2="29" y2="5" stroke="var(--watch)" stroke-width="1.6" stroke-dasharray="5 5"/></svg> Excludes (choose one)</span>
    <span><svg width="34" height="10"><line x1="1" y1="5" x2="27" y2="5" stroke="var(--ink2)" stroke-width="2"/><path d="M26 1 33 5 26 9z" fill="var(--ink2)"/></svg> Leads to</span>
    <span><svg width="22" height="22"><circle cx="11" cy="11" r="9" fill="none" stroke="var(--brass)" stroke-width="2.4" stroke-dasharray="4 3"/></svg> Can research now</span>""")

open(p, 'w', encoding='utf-8').write(s)
d = open('techtree.json', encoding='utf-8').read().replace('</', '<\\/')
open('bannerhold-techtree.html', 'w', encoding='utf-8').write(s.replace('__DATA__', d))
print('ok')
