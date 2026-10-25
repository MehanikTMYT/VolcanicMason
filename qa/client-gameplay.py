#!/usr/bin/env python3
"""Client connected to the isolated QA server; screenshots record manual GUI automation."""
import pathlib, subprocess, os, signal, time
from PIL import ImageGrab
root=pathlib.Path(__file__).resolve().parents[1]
env=dict(os.environ,DISPLAY=':97',LIBGL_ALWAYS_SOFTWARE='1')
x=subprocess.Popen(['Xvfb',':97','-screen','0','1280x720x24','-nolisten','tcp'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
client=root/'run/client'
client.mkdir(parents=True,exist_ok=True)
options=client/'options.txt'
contents=options.read_text() if options.exists() else ''
contents='\n'.join(line for line in contents.splitlines() if not line.startswith('onboardAccessibility:'))
options.write_text(contents+'\nonboardAccessibility:false\n')
log=root/'docs/evidence/client-gameplay.log'
try:
    time.sleep(1)
    with log.open('w') as out:
        p=subprocess.Popen(['./gradlew','runClient','-PqaServer=127.0.0.1:25941','--offline','--no-configuration-cache'],cwd=root,env=env,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            end=time.monotonic()+900
            while time.monotonic()<end and p.poll() is None:
                time.sleep(10)
                ImageGrab.grab(xdisplay=':97').save(root/'docs/evidence/client-gameplay.png')
            print('Client process exit:',p.poll(),flush=True)
        finally:
            if p.poll() is None:
                os.killpg(p.pid,signal.SIGTERM)
                p.wait(timeout=30)
finally:
    x.terminate(); x.wait(timeout=10)
