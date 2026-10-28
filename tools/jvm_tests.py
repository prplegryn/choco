#!/usr/bin/env python3
"""Run protocol/security/HTTP tests on Termux without its incompatible aapt2.

First compile main Kotlin with local_check.sh :app:compileDebugKotlin
-x :app:processDebugResources. CI uses the normal Android Gradle test task.
"""
from pathlib import Path
import subprocess
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
TEMP=ROOT/'.runtime/jvm-check'
TEMP.mkdir(parents=True,exist_ok=True)

def jars(group,artifact,version):return list((CACHE/group/artifact/version).glob('*/*.jar'))
def dependency(group,artifact,version):
    found=jars(group,artifact,version)
    if found:return found[0]
    dest=TEMP/(artifact+'-'+version+'.jar')
    if not dest.exists():
        url='https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+artifact+'/'+version+'/'+dest.name
        with urllib.request.urlopen(url,timeout=60) as r:dest.write_bytes(r.read())
    return dest

compiler=[]
for art in ['kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable']:compiler+=jars('org.jetbrains.kotlin',art,'2.2.20')
compiler+=jars('org.jetbrains.kotlin','kotlin-reflect','1.6.10')+list((CACHE/'org.jetbrains/annotations').glob('*/*/*.jar'))
compiler+=jars('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2')
deps=[dependency(*item) for item in [
    ('org.jetbrains.kotlin','kotlin-stdlib','2.2.20'),('org.jetbrains','annotations','23.0.0'),
    ('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3'),('org.json','json','20250517'),
    ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),('com.squareup.okhttp3','okhttp','4.12.0'),
    ('com.squareup.okhttp3','mockwebserver','4.12.0'),('com.squareup.okio','okio-jvm','3.6.0')]]
deps += [ROOT/'app/build/tmp/kotlin-classes/debug',ROOT/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes',Path.home()/'.android/sdk/platforms/android-36/android.jar']
cp=':'.join(map(str,deps))
sources=list((ROOT/'app/src/test/java').rglob('*.kt'))
cmd=['java','-Xmx1000m','-Dkotlin.colors.enabled=false','-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(TEMP/'tests.jar')]+list(map(str,sources))
subprocess.run(cmd,check=True)
subprocess.run(['java','-cp',str(TEMP/'tests.jar')+':'+cp,'org.junit.runner.JUnitCore','app.chocola.ProtocolTest','app.chocola.PermissionTest','app.chocola.ClientTest'],check=True)
