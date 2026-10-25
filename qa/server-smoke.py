#!/usr/bin/env python3
"""Isolated .81 dedicated server; repeated invocation verifies the same world after restart."""
import pathlib, subprocess, time, sys, socket, struct, json, shutil, os
root=pathlib.Path(__file__).resolve().parents[1]
run=root/'run/server'
run.mkdir(parents=True,exist_ok=True)
autonomous='--autonomous' in sys.argv
world='vmqa-autonomous' if autonomous else 'vmqa-world'
if autonomous:
    assert not (run/world).exists(), 'Move the prior autonomous test world before running again'
    shutil.copytree(run/'vmqa-world',run/world)
(run/'mods').mkdir(exist_ok=True)
shutil.copy2(root/'build/libs/volcanicmason-1.0.3-26.1.2-qa.jar', run/'mods/vmqa.jar')
(run/'eula.txt').write_text('eula=true\n')
(run/'server.properties').write_text(f'online-mode=false\npause-when-empty-seconds=-1\nserver-ip=127.0.0.1\nserver-port=25941\nenable-rcon=true\nrcon.port=25942\nrcon.password=vmqa-local-only\nview-distance=2\nsimulation-distance=2\nlevel-name={world}\nlevel-type=minecraft:flat\n'+'generator-settings='+json.dumps({'biome':'minecraft:plains','layers':[{'height':1,'block':'minecraft:bedrock'},{'height':2,'block':'minecraft:dirt'},{'height':1,'block':'minecraft:grass_block'}],'lakes':False,'features':False})+'\n')
def rcon(command):
    with socket.create_connection(('127.0.0.1',25942),timeout=10) as s:
        def packet(kind,text):
            data=struct.pack('<ii',7,kind)+text.encode()+b'\0\0'
            s.sendall(struct.pack('<i',len(data))+data)
            size=struct.unpack('<i',s.recv(4))[0]
            out=b''
            while len(out)<size: out+=s.recv(size-len(out))
            return out
        packet(3,'vmqa-local-only')
        return packet(2,command)
name=sys.argv[1] if len(sys.argv)>1 else 'server-first'
log=root/'docs/evidence'/f'{name}.log'
with log.open('w') as out:
    proc=subprocess.Popen(['./gradlew','runServer','-PrunWithEmi=false','--offline','--no-configuration-cache'],cwd=root,env=dict(os.environ,VMQA_SCENARIO='autonomous' if autonomous else 'handlers'),stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
    try:
        deadline=time.monotonic()+(1800 if autonomous else 600)
        while time.monotonic()<deadline and proc.poll() is None:
            data=log.read_text(errors='replace')
            if 'RCON running' in data and ('VMQA COMPLETE' in data or 'VMQA FAIL' in data):
                time.sleep(2)
                rcon('save-all flush')
                rcon('stop')
                break
            if 'BUILD FAILED' in data: break
            time.sleep(1)
        proc.wait(timeout=90)
        data=log.read_text(errors='replace')
        assert proc.returncode==0 and 'VMQA COMPLETE' in data and 'VMQA FAIL' not in data and 'All dimensions are saved' in data, str(log)
        print(name,'PASS')
    finally:
        if proc.poll() is None:
            import os,signal
            os.killpg(proc.pid,signal.SIGTERM)
            proc.wait(timeout=30)
