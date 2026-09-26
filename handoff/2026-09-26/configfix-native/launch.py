from pathlib import Path
import shutil, subprocess, os, time, hashlib, json
root=Path('/root/codex-bannerhold-native-20260926')
work=root/'configfix-da67b84'
assert not work.exists(), 'Inspect prior attempt before retry'
work.mkdir()
old=root/'classes/java/main'
classes=work/'classes'
shutil.copytree(old,classes)
lines=(root/'launch-template.txt').read_text().splitlines()
def vals(prefix): return [x[len(prefix):] for x in lines if x.startswith(prefix)]
cp=vals('CP=')
source=Path('/mnt/c/Users/tobia/Hearthstead-Claude/build-codex/configfix-native/HearthsteadClientConfig.java')
result=subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','-proc:none','-cp',':'.join(cp),'-d',str(classes),str(source)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr)
assert result.returncode==0,result.stderr
changed=[]
for f in classes.rglob('*'):
 if f.is_file():
  rel=f.relative_to(classes)
  if f.read_bytes()!=(old/rel).read_bytes(): changed.append(str(rel))
assert changed and all(x.startswith('com/hearthstead/HearthsteadClientConfig') for x in changed),changed
legacy=(root/'moddev/clientLegacyClasspath.txt').read_text().replace(str(old),str(classes))
(work/'legacy.txt').write_text(legacy)
vm=(root/'moddev/clientRunVmArgs.txt').read_text().replace(str(root/'moddev/clientLegacyClasspath.txt'),str(work/'legacy.txt'))
(work/'vm.txt').write_text(vm)
game=work/'game'
game.mkdir()
assert not list(game.iterdir())
display=next(n for n in range(93,100) if not Path('/tmp/.X'+str(n)+'-lock').exists() and not Path('/tmp/.X11-unix/X'+str(n)).exists())
xvfb=subprocess.Popen(['Xvfb',':'+str(display),'-screen','0','1920x1080x24'],stdout=open(work/'xvfb.log','wb'),stderr=subprocess.STDOUT,start_new_session=True)
for _ in range(30):
 if Path('/tmp/.X11-unix/X'+str(display)).exists():break
 assert xvfb.poll() is None
 time.sleep(.1)
env=os.environ.copy();env.update(DISPLAY=':'+str(display),ALSOFT_DRIVERS='null')
cp=[x.replace(str(old),str(classes)) for x in cp]
jvm=[x.replace(str(old),str(classes)) for x in vals('JVM=')]
args=['nice','-n','5',vals('EXE=')[0],'-Xmx3G','@'+str(work/'vm.txt')]+jvm+['-cp',':'.join(cp),vals('MAIN=')[0]]+vals('ARG=')
client=subprocess.Popen(args,cwd=game,env=env,stdout=open(work/'client.log','wb'),stderr=subprocess.STDOUT,start_new_session=True)
meta={'commit':'da67b842ca143246e9f5e7951dccf99edfac6cd5','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'changed_compiled_files':changed,'baseline':'current-verification frozen-identity.json','game_initially_empty':True,'client_pid':client.pid,'xvfb_pid':xvfb.pid,'display':':'+str(display),'game':str(game),'command':args}
(work/'identity.json').write_text(json.dumps(meta,indent=2))
print(json.dumps({k:v for k,v in meta.items() if k!='command'}))