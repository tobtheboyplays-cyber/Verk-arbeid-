import math
# North-facing AF chair at (6,1,7); table at (6,1,6); aisle east.
# Direct port of TavernSeatMotion's frame and mesh transformations, then conservative AABB broad-phase.
F=(0.,0.,-1.); SIDE=(1.,0.,0.); L=(-1.,0.,0.); START=(7.5,1.,7.25); YAW=180.
CHAIR=[(6.125,1,7.125,6.875,1.4375,7.875),(6.125,1.4375,7.75,6.875,2,7.875)]
TABLE=[(6,1.8125,6,7,2,7),(6.875,1,6,7,1.875,6.125),(6.875,1,6.875,7,1.875,7),(6,1,6.875,6.125,1.875,7),(6,1,6,6.125,1.875,6.125)]
def add(a,b):return tuple(x+y for x,y in zip(a,b))
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def sc(a,s):return tuple(x*s for x in a)
def sm(v):v=max(0,min(1,v));return v*v*(3-2*v)
def rot(v,x,y,z):
 q=(v[0],v[1]*math.cos(x)-v[2]*math.sin(x),v[1]*math.sin(x)+v[2]*math.cos(x));r=(q[0]*math.cos(y)+q[2]*math.sin(y),q[1],-q[0]*math.sin(y)+q[2]*math.cos(y));return (r[0]*math.cos(z)-r[1]*math.sin(z),r[0]*math.sin(z)+r[1]*math.cos(z),r[2])
def inv(v,x,y,z):
 q=(v[0]*math.cos(z)+v[1]*math.sin(z),-v[0]*math.sin(z)+v[1]*math.cos(z),v[2]);r=(q[0]*math.cos(y)-q[2]*math.sin(y),q[1],q[0]*math.sin(y)+q[2]*math.cos(y));return (r[0],r[1]*math.cos(x)+r[2]*math.sin(x),-r[1]*math.sin(x)+r[2]*math.cos(x))
def euler(x,y,z):
 pitch=math.asin(max(-1,min(1,-x[2])))
 return (math.atan2(-z[1],y[1]),pitch,math.atan2(x[1],x[0])) if abs(math.cos(pitch))>=1e-7 else (math.atan2(-z[1],y[1]),pitch,0)
def toModel(origin,point):
 a=math.radians(YAW);d=sub(point,origin);return ((math.cos(a)*d[0]+math.sin(a)*d[2])*16,(1.501-d[1])*16-24,(math.sin(a)*d[0]-math.cos(a)*d[2])*16)
def toWorld(origin,point):
 a=math.radians(YAW);return add(origin,((math.cos(a)*point[0]+math.sin(a)*point[2])/16,1.501-(point[1]+24)/16,(math.sin(a)*point[0]-math.cos(a)*point[2])/16))
def solve(origin,bend,foot,side):
 if bend<1:
  ang=math.pi/2*bend;return (-ang,0,0,ang,0,0,0,0,0)
 ankle=toModel(origin,add(add(foot,sc((0,0,-1),-.125)),(0,.126,0))) # foot - f*.125 + ankle lift, f north
 hip=(side*2.6,-12,0);d=sub(ankle,hip);h=math.hypot(d[0],d[2]);lr=36-d[1]*d[1]
 if h<1e-7 or lr < -1e-5:return None
 along=(16-max(0,lr)+h*h)/(2*h)
 if abs(along)>4+1e-5:return None
 height=math.sqrt(max(0,16-along*along));ax=d[0]/h;az=d[2]/h
 k1=add(hip,(along*ax-height*az,0,along*az+height*ax));k2=add(hip,(along*ax+height*az,0,along*az-height*ax));k=k1 if side>0 else k2
 upper=sub(k,hip);lower=sub(ankle,k);x=-math.pi/2;y=math.atan2(-upper[0],-upper[2]);gx=math.atan2(lower[2],math.hypot(lower[0],lower[1]));gz=math.atan2(-lower[0],lower[1]);
 sx=inv(rot((1,0,0),gx,0,gz),x,y,0);sy=inv(rot((0,1,0),gx,0,gz),x,y,0);sz=inv(rot((0,0,1),gx,0,gz),x,y,0);shin=euler(sx,sy,sz);boot=euler(inv((1,0,0),gx,0,gz),inv((0,1,0),gx,0,gz),inv((0,0,1),gx,0,gz));return (x,y,0,*shin,*boot)
def sample(t,cap=None,translate=False):
 p=max(0,min(1,(t-8)/36));bend=sm(t/8);sl=.4375*bend;step=min(5,int(p*6));u=1 if p==1 else p*6-step;ease=sm(u);leftLeads=True;movingLeft=((step%2)==0)==leftLeads
 ls=((step+(1 if leftLeads else 0))//2)/3;rs=((step+(0 if leftLeads else 1))//2)/3
 if movingLeft:ls+=ease/3
 else:rs+=ease/3
 w=math.sin(math.pi*u)**2;lift=.05*w*w
 left=add(add(add(START,sc(L,.1625)),sc(SIDE,-ls)),(0,sl+(lift if movingLeft else 0),0));right=add(add(add(START,sc(L,-.1625)),sc(SIDE,-rs)),(0,sl+(0 if movingLeft else lift),0))
 cs=(ls+rs)/2+(1 if movingLeft==leftLeads else -1)*.10*w;lateral=abs((rs if movingLeft else ls)-cs);ur=math.sqrt(max(0,.25*.25-lateral*lateral));forward=ur*math.sin(math.pi/2*bend)
 if cap is not None:forward=min(forward,cap)
 hip=.5+ur*math.cos(math.pi/2*bend);origin=add(add(add(START,sc(SIDE,-cs)),sc(F,( -.125*bend-forward))), (0,hip-.75+sl,0))
 # note sc(F,-value) is start - F*value
 if translate:
  q=(.25-math.sqrt(5)/12)*sm(t/8)
  v=sc(F,q)
  origin=add(origin,v); left=add(left,v); right=add(right,v)
 return origin,bend,left,right,solve(origin,bend,right,-1),solve(origin,bend,left,1)
def box(origin,bend,leg,side,part,co):
 x0,y0,z0,x1,y1,z1=co;cs=[]
 for i in range(8):
  v=((x0 if not i&1 else x1),(y0 if not i&2 else y1),(z0 if not i&4 else z1))
  if leg:
   if part==2:v=add(v,rot(add(rot((0,6,0),leg[3],leg[4],leg[5]),(0,4,0)),leg[0],leg[1],leg[2]))
   else:
    if part==1:v=add(rot(v,leg[3],leg[4],leg[5]),(0,4,0))
    v=rot(v,leg[0],leg[1],leg[2])
   v=add(v,(side*2.6,-12,2*(1-bend)))
  cs.append(toWorld(origin,v))
 return (min(v[0] for v in cs),min(v[1] for v in cs),min(v[2] for v in cs),max(v[0] for v in cs),max(v[1] for v in cs),max(v[2] for v in cs))
def overlap(a,b):return a[0]<b[3]-1e-5 and a[3]>b[0]+1e-5 and a[1]<b[4]-1e-5 and a[4]>b[1]+1e-5 and a[2]<b[5]-1e-5 and a[5]>b[2]+1e-5
def test(cap,translate=False):
 hits=[]
 for j in range(89):
  t=j*.5;o,b,l,r,rl,ll=sample(t,cap,translate)
  if not rl or not ll:hits.append((t,'ik'));continue
  boxes=[box(o,b,None,0,0,(-5,-24,-2.5,5,-12,2.5)),box(o,b,None,0,0,(-4.6,-32.6,-4.6,4.6,-23.4,4.6))]
  for side,leg in [(-1,rl),(1,ll)]: boxes += [box(o,b,leg,side,k,(-2,0,-4,2,6,0) if k<2 else (-2,0,-4,2,2,0)) for k in range(3)]
  for bi,x in enumerate(boxes):
   for name,solids in [('chair',CHAIR),('table',TABLE)]:
    if any(overlap(x,s) for s in solids):hits.append((t,bi,name,tuple(round(n,4) for n in x)))
 return hits
for cap,translate in [(None,False),(None,True)]:
 h=test(cap,translate);print('cap',cap,'translate',translate,'hits',len(h),h[:20]);print('final',tuple(round(v,6) for v in sample(44,cap,translate)[0]))