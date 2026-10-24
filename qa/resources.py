#!/usr/bin/env python3
"""Validate shipped resources and upstream license/blueprint preservation."""
import pathlib, json, zipfile, subprocess, struct
root=pathlib.Path(__file__).resolve().parents[1]
jar=root/'build/libs/volcanicmason-1.0.3-26.1.2.jar'
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    for name in names:
        if name.endswith('.json'): json.loads(z.read(name))
    assert not any('/qa/' in n or n.startswith('dev/ferriarnus/volcanicmason/qa') for n in names)
    assert z.read('LICENSE')==(root/'LICENSE').read_bytes()
    assert z.read('LICENSE')==subprocess.check_output(['git','show','a2f0b0e:LICENSE'],cwd=root)
    for name in names:
        if name.endswith('.class'):
            assert struct.unpack('>H',z.read(name)[6:8])[0]==69, name
    blueprints=[n for n in names if n.endswith('.blueprint')]
    assert len(blueprints)==10
    for name in blueprints:
        assert z.read(name)==subprocess.check_output(['git','show','a2f0b0e:src/main/resources/'+name],cwd=root)
    for tree in ['technology','effects']:
        assert len([n for n in names if n.startswith('data/volcanicmason/researches/'+tree+'/') and n.endswith('.json')])==5
    assert 'assets/volcanicmason/items/volcanic_mason.json' in names
    assert 'data/volcanicmason/recipe/volcanic_mason.json' in names
    assert 'data/minecraft/tags/block/mineable/axe.json' in names
    assert len([n for n in names if n.startswith('data/volcanicmason/crafterrecipes/') and n.endswith('.json')])==2
    print('PASS: JSON syntax, Java 25 bytecode, release excludes QA, LGPL license unchanged, all 10 upstream blueprints byte-identical, 5 research/effect pairs, recipes, item model and block tag present')
