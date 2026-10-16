"""Build an independent, permission-free Android video load probe for capture comparisons."""
import os
import subprocess
from pathlib import Path

sdk = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
build = Path('work/virtual-stream-network/load-probe').resolve()
build.mkdir(parents=True, exist_ok=True)
classes = build / 'classes'
dex = build / 'dex'
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)
source = build / 'LoadProbe.java'
source.write_text('''package com.alkaidlab.sunshine.loadprobe;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.*;
import android.view.*;
public class LoadProbe extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(128);setContentView(new LoadView());}
 class LoadView extends View {
  Paint paint=new Paint();Bitmap pattern;long frame;
  LoadView(){super(LoadProbe.this);int w=1024,h=1024;int[] pixels=new int[w*h];int seed=31;
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){seed=seed*1664525+1013904223;int c=seed>>>8;pixels[y*w+x]=0xff000000|c;}
   pattern=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);paint.setFilterBitmap(true);}
  protected void onDraw(Canvas c){long now=SystemClock.uptimeMillis();float offset=(now/10)%1024;
   c.save();c.scale(getWidth()/1024f,getHeight()/1024f);
   c.drawBitmap(pattern,-offset,0,paint);c.drawBitmap(pattern,1024-offset,0,paint);c.restore();
   paint.setColor(Color.BLACK);c.drawRect(0,0,getWidth(),90,paint);paint.setColor(Color.WHITE);paint.setTextSize(32);
   c.drawText("VIDEO LOAD  frame="+(++frame)+" display="+getDisplay().getDisplayId(),20,50,paint);postInvalidateOnAnimation();}
 }
}
''', encoding='utf-8')
manifest = build / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.alkaidlab.sunshine.loadprobe"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/><application android:label="Video Load Probe" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"><activity android:name=".LoadProbe" android:exported="true" android:resizeableActivity="true" android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"/></application></manifest>''', encoding='utf-8')
tools = sdk / 'build-tools/34.0.0'
android = sdk / 'platforms/android-34/android.jar'

def run(*command):
    subprocess.run([str(x) for x in command], check=True)

run('javac', '--release', '8', '-cp', android, '-d', classes, source)
run('jar', 'cf', build/'classes.jar', '-C', classes, '.')
run('java', '-cp', tools/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '29', '--lib', android, '--output', dex, build/'classes.jar')
run(tools/'aapt.exe', 'package', '-f', '-M', manifest, '-I', android, '-F', build/'unsigned.apk')
run('jar', 'uf', build/'unsigned.apk', '-C', dex, 'classes.dex')
run(tools/'zipalign.exe', '-f', '4', build/'unsigned.apk', build/'aligned.apk')
run(tools/'apksigner.bat', 'sign', '--ks', Path.home()/'.android/debug.keystore', '--ks-pass', 'pass:android', '--out', build/'probe.apk', build/'aligned.apk')
print(build/'probe.apk')
